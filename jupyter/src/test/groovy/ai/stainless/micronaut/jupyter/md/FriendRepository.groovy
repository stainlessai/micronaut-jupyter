package ai.stainless.micronaut.jupyter.md

import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.annotation.EntityGraph
import io.micronaut.data.repository.CrudRepository

@Repository
interface FriendRepository extends CrudRepository<Person, Long> {
    @EntityGraph(attributePaths = ["friends"])
    Person find (String firstName, String lastName)
}
