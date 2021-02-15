/**
 * 4lex@27.1.2021
 * OwnSmsSenderConfiguration for configuration own bulk sms provider
 */
package org.whispersystems.textsecuregcm.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import org.hibernate.validator.constraints.NotEmpty;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.List;

public class OwnSmsSenderConfiguration {

  @NotEmpty
  @JsonProperty
  private String accountName;

  @NotEmpty
  @JsonProperty
  private String accountPassword;

  @NotEmpty
  @JsonProperty
  private String accountFrom;

  @NotEmpty
  @JsonProperty
  private String baseUrl;

  @NotNull
  @Valid
  private CircuitBreakerConfiguration circuitBreaker = new CircuitBreakerConfiguration();

  @NotNull
  @Valid
  private RetryConfiguration retry = new RetryConfiguration();

  public String getAccountName() {
    return accountName;
  }

  @VisibleForTesting
  public void setAccountName(String accountName) {
    this.accountName = accountName;
  }

  public String getAccountPassword() {
    return accountPassword;
  }

  @VisibleForTesting
  public void setAccountPassword(String accountPassword) {
    this.accountPassword = accountPassword;
  }

  public String getAccountFrom() {
    return accountFrom;
  }

  @VisibleForTesting
  public void setAccountFrom(String accountFrom) {
    this.accountFrom = accountFrom;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  @VisibleForTesting
  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public CircuitBreakerConfiguration getCircuitBreaker() {
    return circuitBreaker;
  }

  @VisibleForTesting
  public void setCircuitBreaker(CircuitBreakerConfiguration circuitBreaker) {
    this.circuitBreaker = circuitBreaker;
  }

  public RetryConfiguration getRetry() {
    return retry;
  }

  @VisibleForTesting
  public void setRetry(RetryConfiguration retry) {
    this.retry = retry;
  }
}
