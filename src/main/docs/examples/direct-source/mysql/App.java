// tag::source[]
package example;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.runtime.Micronaut;
import pyronaut.build.AppConfig;
import pyronaut.build.Dependency;

@Dependency(group = "io.micronaut.data", module = "micronaut-data-jdbc")
@Dependency(group = "io.micronaut.sql", module = "micronaut-jdbc-hikari")
@Dependency(group = "com.mysql", module = "mysql-connector-j")
@Dependency(
    group = "io.micronaut.data",
    module = "micronaut-data-processor",
    scope = Dependency.Scope.BUILD
)
@AppConfig(name = "datasources.default.db-type", value = "mysql")
@AppConfig(name = "datasources.default.dialect", value = "MYSQL")
@AppConfig(name = "datasources.default.schema-generate", value = "CREATE_DROP")
public class App {
    public static void main(String[] args) {
        Micronaut.run(App.class, args);
    }
}

@MappedEntity
record Book(@Id @GeneratedValue Long id, @NonNull String title) {
}

@JdbcRepository(dialect = Dialect.MYSQL)
interface BookRepository extends CrudRepository<Book, Long> {
}

@Controller("/books")
@ExecuteOn(TaskExecutors.BLOCKING)
final class BookController {
    private final BookRepository books;

    BookController(BookRepository books) {
        this.books = books;
    }

    @Post("/{title}")
    long create(@PathVariable String title) {
        books.save(new Book(null, title));
        return books.count();
    }

    @Get("/count")
    long count() {
        return books.count();
    }
}
// end::source[]
