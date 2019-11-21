package micronaut.examples.md.repositories

import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.annotation.EntityGraph
import micronaut.examples.md.domains.Person
import io.micronaut.data.repository.CrudRepository;

@Repository
interface FriendRepository extends CrudRepository<Person, Long> {
    /*
     * Micronaut doesn't support lazy initialization, so we need to specify a
     * join for our query.
     */
    @EntityGraph(attributePaths = ["friends"])
    Person find (String firstName, String lastName)
}
