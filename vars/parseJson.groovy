import groovy.json.JsonParserType
import groovy.json.JsonSlurper

/*
 * Return a serializable map from JSON text input.
 */
Map call(String json = '') {
    [:] << new JsonSlurper(type: JsonParserType.LAX).parseText(json)
}
