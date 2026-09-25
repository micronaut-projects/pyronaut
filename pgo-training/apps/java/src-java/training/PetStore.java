package training;

import jakarta.inject.Singleton;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-memory pet store, safe for concurrent requests.
 */
@Singleton
public class PetStore {
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

    List<Pet> bySpecies(String species) {
        return pets.values().stream().filter(pet -> pet.species().equals(species)).toList();
    }

    List<Pet> nameContains(String fragment) {
        return pets.values().stream().filter(pet -> pet.name().contains(fragment)).toList();
    }

    long countSpecies(String species) {
        return pets.values().stream().filter(pet -> pet.species().equals(species)).count();
    }
}
