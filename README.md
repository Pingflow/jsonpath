# JSONPath [![Build Status](https://travis-ci.org/gatling/jsonpath.svg?branch=master)](https://travis-ci.org/gatling/jsonpath) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.gatling/jsonpath_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.gatling/jsonpath_2.12/)

This is an implementation of JSONPath in Scala. The library follows the semantics describted by Stefan Goessner on his [blog](http://goessner.net/articles/JSONPath).
Although, due to the lack of standardization, it is worth reading the following documentation. 

# Syntax

## JSONPath expressions

In a JSONPath expression, the abstract name `$` is used to designate to the outer level object.

JSONPath expressions can use the dot–notation `$.store.book[0].title` or the bracket–notation `$['store']['book'][0]['title']`.

Based on the following JSON document : 

```json

    { "store": {
        "book": [ 
          { "category": "reference",
            "author": "Nigel Rees",
            "title": "Sayings of the Century",
            "price": 8.95
          },
          { "category": "fiction",
            "author": "Evelyn Waugh",
            "title": "Sword of Honour",
            "price": 12.99
          },
          { "category": "fiction",
            "author": "Herman Melville",
            "title": "Moby Dick",
            "isbn": "0-553-21311-3",
            "price": 8.99
          },
          { "category": "fiction",
            "author": "J. R. R. Tolkien",
            "title": "The Lord of the Rings",
            "isbn": "0-395-19395-8",
            "price": 22.99
          }
        ],
        "bicycle": {
          "color": "red",
          "price": 19.95
        }
      }
    }
```

Here is a list of supported operators : 

| Operator             | Description                    | Example                                   |
| -------------------- | ------------------------------ | ----------------------------------------- |
| `$`                  | the root element               | `$`                                       |
| `@`                  | the current element            | `@`                                       |
| `.` or `[]`          | the child element              | `$.bicycle or $['bicycle']`               |
| `..`                 | recursive descent              | `$..author`                               |
| `*`                  | wildcard                       | `$.store.book[*].author`                  |
| `[,]`                | collection of names or indices | `$[2,5], $.store.book['title', 'author']` |
| `[start:stop:end]`   | array slice                    | `$[2:-3]`, `$[::-1]`                      |
| `[?(expression)]`    | filter expression              | `$.store.book[?(@.price < 10)].title`     |


## Filter expressions

The underlying engine for writing filter expressions currently support two different kinds of filter expression:

| Filter            | Example                                          |
| ----------------- | -------------------------------------------------|
| Has-child filter  | `$.store.book[?(@.isbn)].title`                  |
| Comparison filter | `$.store.book[?(@.category == 'fiction')].title` |

You can also combine them with boolean operators, eg: `$.store.book[?(@.price < 10 && @.price >4)].title`


## Implementation

This implementation is JSON provider agnostic, but it requires to have JSON representation based on raw types from the JVM.
For instance, with [Jackson](http://jackson.codehaus.org), one could do the following:

```scala
val jsonSample = (new ObjectMapper).readValue("""{"a":"A","b":"B"}""", classOf[Object])
```

`JSONPath.query("$.a", jsonSample, false)` gives you `Right(non-empty iterator)`. This will allow you to iterate over all possible solutions to the query. 

eg :

```scala
JsonPath.query("$.a", jsonSample, false).right.map(_.toVector)
```

gives you `Right(Vector("A"))`

If you need to retrieve a value for non-existing fields you can set the third parameter to true. 
It will return null if no value can be found for the provided path

## Licence

This library is licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).
