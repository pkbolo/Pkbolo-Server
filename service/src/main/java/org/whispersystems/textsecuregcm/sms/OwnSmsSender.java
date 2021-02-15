/*
 * Copyright (C) 2013 Open WhisperSystems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.textsecuregcm.sms;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.configuration.OwnSmsSenderConfiguration;
import org.whispersystems.textsecuregcm.configuration.CircuitBreakerConfiguration;
import org.whispersystems.textsecuregcm.configuration.RetryConfiguration;
import org.whispersystems.textsecuregcm.http.FaultTolerantHttpClient;
import org.whispersystems.textsecuregcm.http.FormDataBodyPublisher;
import org.whispersystems.textsecuregcm.util.Base64;
import org.whispersystems.textsecuregcm.util.Constants;
import org.whispersystems.textsecuregcm.util.ExecutorUtils;
import org.whispersystems.textsecuregcm.util.SystemMapper;
import org.whispersystems.textsecuregcm.util.Util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;

import static com.codahale.metrics.MetricRegistry.name;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class OwnSmsSender implements Transmitter {

  private static final Logger         logger         = LoggerFactory.getLogger(OwnSmsSender.class);

  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
  private final Meter          smsMeter       = metricRegistry.meter(name(getClass(), "sms", "delivered"));
  private final Meter          voxMeter       = metricRegistry.meter(name(getClass(), "vox", "delivered"));
  private final Meter          priceMeter     = metricRegistry.meter(name(getClass(), "price"));

  private final String         accountName;
  private final String         accountPassword;
  private final String         accountFrom;

  private final FaultTolerantHttpClient httpClient;
  private final String                  smsUri;
  private final URI                     voxUri;

  public OwnSmsSender(OwnSmsSenderConfiguration config) {
    Executor executor = ExecutorUtils.newFixedThreadBoundedQueueExecutor(10, 100);

    this.accountName         = config.getAccountName();
    this.accountPassword     = config.getAccountPassword();
    this.accountFrom         = config.getAccountFrom();
    this.smsUri              = config.getBaseUrl();
    this.voxUri              = URI.create(config.getBaseUrl());
    this.httpClient          = FaultTolerantHttpClient.newBuilder()
                                                      .withCircuitBreaker(config.getCircuitBreaker())
                                                      .withRetry(config.getRetry())
                                                      .withVersion(HttpClient.Version.HTTP_2)
                                                      .withConnectTimeout(Duration.ofSeconds(10))
                                                      .withRedirect(HttpClient.Redirect.NEVER)
                                                      .withExecutor(executor)
                                                      .withName("own_sms_sender")
                                                      .build();
  }

  public CompletableFuture<Boolean> deliverSmsVerification(String destination, Optional<String> clientType, String verificationCode) {
    logger.info("4LEX deliverSmsVerification verificationCode is: " + verificationCode);

    Map<String, String> requestParameters = new HashMap<>(); 
    requestParameters.put("name", this.accountName);
    requestParameters.put("password", this.accountPassword);
    requestParameters.put("to", destination);
    requestParameters.put("from", this.accountFrom);

    if ("ios".equals(clientType.orElse(null))) {
      requestParameters.put("text", String.format(SmsSender.SMS_IOS_VERIFICATION_TEXT, verificationCode, verificationCode));
    } else if ("android-ng".equals(clientType.orElse(null))) {
      requestParameters.put("text", String.format(SmsSender.SMS_ANDROID_NG_VERIFICATION_TEXT, verificationCode));
    } else {
      requestParameters.put("text", String.format(SmsSender.SMS_VERIFICATION_TEXT, verificationCode));
    }

    String encodedURL = requestParameters.keySet().stream()
      .map(key -> key + "=" + encodeValue(requestParameters.get(key)))
      .collect(Collectors.joining("&", this.smsUri + "?", ""));


    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(encodedURL))
                                     .build();

    smsMeter.mark();

    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                     .thenApply(this::parseResponse)
                     .handle(this::processResponse);
  }

  public CompletableFuture<Boolean> deliverVoxVerification(String destination, String verificationCode, Optional<String> locale) {
    logger.info("OwnSmsSender deliverVoxVerification -- not implemented");
    return null;
  }

  private String encodeValue(String value) {
    try {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
    } catch (UnsupportedEncodingException e) {
	logger.info("Error encoding parameter: " + e.getMessage());
    }
    return "";
  }

  private boolean processResponse(OwnSMSResponse response, Throwable throwable) {
    if (response != null && response.isSuccess()) {
      priceMeter.mark((long)(response.successResponse.price * 1000));
      return true;
    } else if (response != null && response.isFailure()) {
      logger.info("OwnSMS request failed: " + response.failureResponse.status + ", " + response.failureResponse.message);
      return false;
    } else if (throwable != null) {
      logger.info("OwnSMS request failed", throwable);
      return false;
    } else {
      logger.warn("No response or throwable!");
      return false;
    }
      }

  private OwnSMSResponse parseResponse(HttpResponse<String> response) {
    ObjectMapper mapper = SystemMapper.getMapper();

    if (response.statusCode() >= 200 && response.statusCode() < 300) {
      if ("application/json".equals(response.headers().firstValue("Content-Type").orElse(null))) {
        return new OwnSMSResponse(OwnSMSResponse.OwnSMSSuccessResponse.fromBody(mapper, response.body()));
      } else {
        return new OwnSMSResponse(new OwnSMSResponse.OwnSMSSuccessResponse());
      }
    }

    if ("application/json".equals(response.headers().firstValue("Content-Type").orElse(null))) {
      return new OwnSMSResponse(OwnSMSResponse.OwnSMSFailureResponse.fromBody(mapper, response.body()));
    } else {
      return new OwnSMSResponse(new OwnSMSResponse.OwnSMSFailureResponse());
    }
  }

  public static class OwnSMSResponse {

    private OwnSMSSuccessResponse successResponse;
    private OwnSMSFailureResponse failureResponse;

    OwnSMSResponse(OwnSMSSuccessResponse successResponse) {
      this.successResponse = successResponse;
    }

    OwnSMSResponse(OwnSMSFailureResponse failureResponse) {
      this.failureResponse = failureResponse;
    }

    boolean isSuccess() {
      return successResponse != null;
    }

    boolean isFailure() {
      return failureResponse != null;
    }

    private static class OwnSMSSuccessResponse {
      @JsonProperty
      private double price;

      static OwnSMSSuccessResponse fromBody(ObjectMapper mapper, String body) {
        try {
          return mapper.readValue(body, OwnSMSSuccessResponse.class);
        } catch (IOException e) {
          logger.warn("Error parsing OwnSMS success response: " + e);
          return new OwnSMSSuccessResponse();
        }
      }
    }

    private static class OwnSMSFailureResponse {
      @JsonProperty
      private int status;

      @JsonProperty
      private String message;

      static OwnSMSFailureResponse fromBody(ObjectMapper mapper, String body) {
        try {
          return mapper.readValue(body, OwnSMSFailureResponse.class);
        } catch (IOException e) {
          logger.warn("Error parsing OwnSMS success response: " + e);
          return new OwnSMSFailureResponse();
        }
      }
    }
  }
}
