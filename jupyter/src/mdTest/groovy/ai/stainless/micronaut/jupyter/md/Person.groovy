package ai.stainless.micronaut.jupyter.md

import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.Id
import javax.persistence.ManyToMany

@Entity
public class Person {
    @Id
    @GeneratedValue
    Long id

    String firstName
    String lastName

    @ManyToMany
    List<Person> friends = []

    Person() {

    }

    Person(String first, String last) {
        firstName = first
        lastName = last
    }
}
