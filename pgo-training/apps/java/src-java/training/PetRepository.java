package training;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@JdbcRepository(dialect = Dialect.H2)
public interface PetRepository extends CrudRepository<Pet, Long> {

    List<Pet> findBySpecies(String species);

    List<Pet> findByNameContains(String fragment);

    long countBySpecies(String species);
}
