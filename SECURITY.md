# Security policy

## Supported versions

Only the latest release of this fork receives security fixes. Upstream releases (3.0.5 and older) are not
maintained here.

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub:
[Report a vulnerability](https://github.com/efim0v/keycloak-event-listener-rabbitmq/security/advisories/new)
(the "Security" tab of this repository). Do not open a public issue for a vulnerability.

Include the affected version, the Keycloak and RabbitMQ versions, the configuration involved (without
passwords) and the steps to reproduce. Fixes are released as a new version and announced in a GitHub security
advisory and in [CHANGELOG.md](CHANGELOG.md).

Vulnerabilities in Keycloak, RabbitMQ or the RabbitMQ Java client should be reported to those projects;
this fork picks up fixed `amqp-client` releases through Dependabot.
