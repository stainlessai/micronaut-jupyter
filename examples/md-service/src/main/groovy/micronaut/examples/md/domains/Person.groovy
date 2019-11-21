package micronaut.examples.md.domains

import javax.persistence.ManyToMany

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;

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
