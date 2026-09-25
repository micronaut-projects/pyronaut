package training;

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
import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Direct-source PGO training app for pyronaut-dev: {@code pyronaut run App.java}.
 */
public class App {
    public static void main(String[] args) {
        Micronaut.run(App.class, args);
    }
}

@Serdeable
record Pet(Long id, String name, String species, int age, boolean vaccinated) {
}

@Serdeable
record PetCommand(@NotBlank String name, @NotBlank String species, @Min(0) int age, boolean vaccinated) {
}

@Singleton
class PetStore {
    private final ConcurrentMap<Long, Pet> pets = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    Pet save(Pet pet) {
        Pet saved = pet.id() == null
            ? new Pet(nextId.getAndIncrement(), pet.name(), pet.species(), pet.age(), pet.vaccinated())
            : pet;
        pets.put(saved.id(), saved);
        return saved;
    }

    Optional<Pet> find(Long id) {
        return Optional.ofNullable(pets.get(id));
    }

    boolean exists(Long id) {
        return pets.containsKey(id);
    }

    void delete(Long id) {
        pets.remove(id);
    }

    List<Pet> all() {
        return List.copyOf(pets.values());
    }
}

@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
class PetController {
    private final PetStore store;

    PetController(PetStore store) {
        this.store = store;
    }

    @Get(value = "/hello", produces = MediaType.TEXT_PLAIN)
    String hello() {
        return "Hello PGO";
    }

    @Get("/pets")
    List<Pet> list() {
        return store.all();
    }

    @Get("/pets/species/{species}")
    List<Pet> bySpecies(String species) {
        return store.all().stream().filter(pet -> pet.species().equals(species)).toList();
    }

    @Get("/pets/search/{fragment}")
    List<Pet> search(String fragment) {
        return store.all().stream().filter(pet -> pet.name().contains(fragment)).toList();
    }

    @Get("/pets/count/{species}")
    Map<String, Object> count(String species) {
        return Map.of("species", species, "count", store.all().stream().filter(pet -> pet.species().equals(species)).count());
    }

    @Get("/pets/{id}")
    HttpResponse<?> show(Long id) {
        return store.find(id)
            .<HttpResponse<?>>map(HttpResponse::ok)
            .orElseGet(() -> HttpResponse.notFound(Map.of("message", "No pet " + id)));
    }

    @Post("/pets")
    HttpResponse<Pet> create(@Body @Valid PetCommand command) {
        return HttpResponse.created(store.save(new Pet(null, command.name(), command.species(), command.age(), command.vaccinated())));
    }

    @Put("/pets/{id}")
    HttpResponse<?> update(Long id, @Body @Valid PetCommand command) {
        if (!store.exists(id)) {
            return HttpResponse.notFound(Map.of("message", "No pet " + id));
        }
        return HttpResponse.ok(store.save(new Pet(id, command.name(), command.species(), command.age(), command.vaccinated())));
    }

    @Delete("/pets/{id}")
    HttpResponse<?> delete(Long id) {
        if (!store.exists(id)) {
            return HttpResponse.notFound(Map.of("message", "No pet " + id));
        }
        store.delete(id);
        return HttpResponse.noContent();
    }
}
