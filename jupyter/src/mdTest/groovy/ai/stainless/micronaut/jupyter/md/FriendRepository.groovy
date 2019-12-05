package ai.stainless.micronaut.jupyter.md

import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.annotation.EntityGraph
import io.micronaut.data.repository.CrudRepository

@Repository
interface FriendRepository extends CrudRepository<Person, Long> {
    /*
     * Micronaut doesn't support lazy initialization, so we need to specify a
     * join for our query.
     */
    @EntityGraph(attributePaths = ["friends"])
    Person find (String firstName, String lastName)
}
