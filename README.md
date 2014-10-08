NewRelic-Scala-Agent-Test
=========================

Testing NewRelic Java Agent in plain Scala

To run:
```
sbt assembly

java -javaagent:<your-newrelic-agent-jar> -jar target/scala-2.11/newrelic_test-assembly-0.1-SNAPSHOT.jar
```
