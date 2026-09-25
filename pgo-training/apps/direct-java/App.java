package training;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.runtime.Micronaut;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import pyronaut.build.AppConfig;
import pyronaut.build.Dependency;

import java.util.List;
import java.util.Map;

/**
 * Direct-source PGO training app for pyronaut-dev: {@code pyronaut run App.java}.
 */
@Dependency(group = "io.micronaut.data", module = "micronaut-data-jdbc")
@Dependency(group = "io.micronaut.sql", module = "micronaut-jdbc-hikari")
@Dependency(group = "com.h2database", module = "h2")
@Dependency(group = "io.micronaut.data", module = "micronaut-data-processor", scope = Dependency.Scope.BUILD)
@AppConfig(name = "datasources.default.url", value = "jdbc:h2:mem:direct;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AppConfig(name = "datasources.default.username", value = "sa")
@AppConfig(name = "datasources.default.password", value = "")
@AppConfig(name = "datasources.default.driver-class-name", value = "org.h2.Driver")
@AppConfig(name = "datasources.default.dialect", value = "H2")
@AppConfig(name = "datasources.default.schema-generate", value = "CREATE_DROP")
public class App {
    public static void main(String[] args) {
        Micronaut.run(App.class, args);
    }
}

@MappedEntity
@Serdeable
record Pet(@Id @GeneratedValue Long id, String name, String species, int age, boolean vaccinated) {
}

@Serdeable
record PetCommand(@NotBlank String name, @NotBlank String species, @Min(0) int age, boolean vaccinated) {
}

@JdbcRepository(dialect = Dialect.H2)
interface PetRepository extends CrudRepository<Pet, Long> {
    List<Pet> findBySpecies(String species);

    List<Pet> findByNameContains(String fragment);

    long countBySpecies(String species);
}

@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
class PetController {
    private final PetRepository repository;

    PetController(PetRepository repository) {
        this.repository = repository;
    }

    @Get(value = "/hello", produces = MediaType.TEXT_PLAIN)
    String hello() {
        return "Hello PGO";
    }

    @Get("/pets")
    Iterable<Pet> list() {
        return repository.findAll();
    }

    @Get("/pets/species/{species}")
    List<Pet> bySpecies(String species) {
        return repository.findBySpecies(species);
    }

    @Get("/pets/search/{fragment}")
    List<Pet> search(String fragment) {
        return repository.findByNameContains(fragment);
    }

    @Get("/pets/count/{species}")
    Map<String, Object> count(String species) {
        return Map.of("species", species, "count", repository.countBySpecies(species));
    }

    @Get("/pets/{id}")
    HttpResponse<?> show(Long id) {
        return repository.findById(id)
            .<HttpResponse<?>>map(HttpResponse::ok)
            .orElseGet(() -> HttpResponse.notFound(Map.of("message", "No pet " + id)));
    }

    @Post("/pets")
    HttpResponse<Pet> create(@Body @Valid PetCommand command) {
        return HttpResponse.created(repository.save(new Pet(null, command.name(), command.species(), command.age(), command.vaccinated())));
    }

    @Put("/pets/{id}")
    HttpResponse<?> update(Long id, @Body @Valid PetCommand command) {
        if (!repository.existsById(id)) {
            return HttpResponse.notFound(Map.of("message", "No pet " + id));
        }
        return HttpResponse.ok(repository.update(new Pet(id, command.name(), command.species(), command.age(), command.vaccinated())));
    }

    @Delete("/pets/{id}")
    HttpResponse<?> delete(Long id) {
        if (!repository.existsById(id)) {
            return HttpResponse.notFound(Map.of("message", "No pet " + id));
        }
        repository.deleteById(id);
        return HttpResponse.noContent();
    }
}
