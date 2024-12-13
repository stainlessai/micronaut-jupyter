package micronaut.examples.md.domains

import jakarta.persistence.ManyToMany

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

@Entity
public class Person {
    @Id
    @GeneratedValue
    Long id

    String firstName
    String lastName

    @ManyToMany
    List<Person> friends = []

    Person () {

    }

    Person (String first, String last) {
        firstName = first
        lastName = last
    }
}
