package micronaut.examples.gorm.services

import grails.gorm.services.Join
import grails.gorm.services.Service
import groovy.transform.CompileStatic
import micronaut.examples.gorm.domains.Person

@CompileStatic
@Service(Person)
interface FriendService {
    Person get (Long id)

    /*
     * No need to use @Join here, since we are specifying a join in our domain
     * mapping so that dynamic finders will work. However, doing it that way
     * means that a join will ALWAYS be performed. Alternatively, we could
     * instead use @Join here and specify a `fetch: [friends: 'join']` param in
     * all of our dynamic finder queries.
     */
    //@Join('friends')
    Person find (String firstName, String lastName)

    List<Person> list(Map args)

    Long count()

    Person save(Person person)
}
