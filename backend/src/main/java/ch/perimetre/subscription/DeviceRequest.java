package ch.perimetre.subscription;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Body of POST /devices and PUT /devices/{id} (token refresh). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeviceRequest(
        @NotBlank @Size(max = 4096) String pushToken,
        @Pattern(regexp = "ios|android") String platform) {
}
