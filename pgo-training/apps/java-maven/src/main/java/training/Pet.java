package training;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record Pet(Long id, String name, String species, int age, boolean vaccinated) {
}
