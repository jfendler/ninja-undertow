mvn -am test -DskipTests=true -Dexec.classpathScope="test" -Dexec.mainClass="ninja.standalone.undertow.NinjaUndertow" -Dninja.external.configuration=src/test/resources/conf/undertow.example.conf exec:java
