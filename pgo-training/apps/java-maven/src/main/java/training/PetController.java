package training;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

@Controller("/pets")
@ExecuteOn(TaskExecutors.BLOCKING)
public class PetController {
    private final PetStore store;

    PetController(PetStore store) {
        this.store = store;
    }

    @Get
    List<Pet> list() {
        return store.all();
    }

    @Get("/species/{species}")
    List<Pet> bySpecies(String species) {
        return store.bySpecies(species);
    }

    @Get("/search/{fragment}")
    List<Pet> search(String fragment) {
        return store.nameContains(fragment);
    }

    @Get("/count/{species}")
    Map<String, Object> count(String species) {
        return Map.of("species", species, "count", store.countSpecies(species));
    }

    @Get("/{id}")
    HttpResponse<?> show(Long id) {
        return store.find(id)
            .<HttpResponse<?>>map(HttpResponse::ok)
            .orElseGet(() -> HttpResponse.notFound(Map.of("message", "No pet " + id)));
    }

    @Post
    HttpResponse<Pet> create(@Body @Valid PetCommand command) {
        return HttpResponse.created(store.save(new Pet(null, command.name(), command.species(), command.age(), command.vaccinated())));
    }

    @Put("/{id}")
    HttpResponse<?> update(Long id, @Body @Valid PetCommand command) {
        if (!store.exists(id)) {
            return HttpResponse.notFound(Map.of("message", "No pet " + id));
        }
        return HttpResponse.ok(store.save(new Pet(id, command.name(), command.species(), command.age(), command.vaccinated())));
    }

    @Delete("/{id}")
    HttpResponse<?> delete(Long id) {
        if (!store.exists(id)) {
            return HttpResponse.notFound(Map.of("message", "No pet " + id));
        }
        store.delete(id);
        return HttpResponse.noContent();
    }

    @Post(value = "/summary", consumes = "text/plain")
    Map<String, Object> summary(@Body String text) {
        return Summaries.summarize(text);
    }
}
