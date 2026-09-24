package training;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Serdeable
public record PetCommand(@NotBlank String name, @NotBlank String species, @Min(0) int age, boolean vaccinated) {
}
