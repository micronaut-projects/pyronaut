package training;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.serde.annotation.Serdeable;

@MappedEntity
@Serdeable
public record Pet(@Id @GeneratedValue Long id, String name, String species, int age, boolean vaccinated) {
}
