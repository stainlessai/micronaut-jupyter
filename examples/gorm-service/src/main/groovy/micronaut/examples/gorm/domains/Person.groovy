package micronaut.examples.gorm.domains

import grails.gorm.annotation.Entity

@Entity
public class Person {

    Long id

    String firstName
    String lastName

    static hasMany = [friends: Person]

    List<Person> friends = []

    /*
     * Micronaut doesn't support lazy initialization, so we need to join our
     * collection so that dynamic finders will work.
     */
    static mapping = {
        friends fetch: 'join'
    }

    Person () {

    }

    Person (String first, String last) {
        firstName = first
        lastName = last
    }
}
