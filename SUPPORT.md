# Support

## Where to go

| You have                                                       | Go to                                                                                                                     |
|----------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| a question about installing, configuring or using the listener | [Discussions, Q&A](https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq/discussions/categories/q-a)          |
| a bug                                                          | [a new issue](https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq/issues/new/choose) (bug report form)      |
| an idea for a change                                           | [a new issue](https://github.com/Mizzle-Studio/keycloak-event-listener-rabbitmq/issues/new/choose) (feature request form) |
| a vulnerability                                                | [SECURITY.md](SECURITY.md); never a public issue                                                                          |

Problems in Keycloak or RabbitMQ themselves belong to those projects.

## What is supported

* The latest release of this fork. Fixes are released as a new version; older releases are not patched.
* The Keycloak and RabbitMQ versions the [CI workflow](.github/workflows/ci.yml) runs the integration test on:
  each Keycloak 26 line at its latest patch release and the Keycloak version the jar is built against. The notes of
  each release list the exact versions it was tested with.
* The newest Keycloak release is tested every week by the
  [Keycloak latest workflow](.github/workflows/keycloak-latest.yml); a failure opens an issue labelled
  `keycloak-latest`.
* Releases of the upstream project (3.0.5 and older) are not maintained here.

## What to expect

Support is best effort. There is no guaranteed response time or fix time. A report that names the versions,
the configuration (without passwords) and the log lines of the listener is the quickest to act on.
