# Kvasir

MCP-Server, der Markdown-Doku, Javadoc und Java-Quellcode mehrerer Projekte projekt- und
versionsbewusst durchsuchbar macht — damit ein AI-Agent aus der tatsächlichen Version zitiert,
statt aus seinem Trainingsstand zu raten.

Benannt nach dem weisesten Wesen der nordischen Mythologie: Kvasir zog durch die Welt, und
niemand konnte ihm eine Frage stellen, die er nicht beantworten konnte.

Die technische Spezifikation steht in [`CLAUDE.md`](CLAUDE.md), das Vorgehen in
[`PLAN.md`](PLAN.md), Vertiefungen unter [`docs/`](docs/).

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/kvasir-mcp-server-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.
