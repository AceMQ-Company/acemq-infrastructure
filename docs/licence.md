# Licence

`acemq-infrastructure` is licensed under the **Apache License, Version 2.0**.
The full text is in [`LICENSE`](https://github.com/AceMQ-Company/acemq-infrastructure/blob/main/LICENSE)
in the repository, and the attribution notices are in
[`NOTICE`](https://github.com/AceMQ-Company/acemq-infrastructure/blob/main/NOTICE).

In short: you may use it, modify it, and distribute it, including commercially,
provided you keep the licence and notice files and state what you changed. There
is **no warranty of any kind**. See sections 7 and 8 of the licence for the
exact wording, which matters more than this paragraph.

## What this repository currently contains

Documentation, shell and Python scripts, and example configuration. No product
code — see [the roadmap](roadmap.md). Everything here is covered by the same
licence.

## Dependencies

There are none yet. When the tool is built, it will depend on
[`acemq-java-rabbitmq-admin`](https://acemq.org/acemq-java-rabbitmq-admin/),
which is Apache-2.0 and part of this project, and on a YAML parser and a CLI
framework whose licences will be recorded in `NOTICE` at that point.

`scripts/blue-green-lab.sh` runs the official `rabbitmq` Docker images. Those
are distributed under their own terms by their publishers and are not
redistributed here.

## Trademarks

RabbitMQ is a trademark of Broadcom Inc. This project is not affiliated with,
endorsed by, or sponsored by Broadcom. Kubernetes is a trademark of The Linux
Foundation. Other names are the property of their respective owners, and are
used here to say what this tool talks to.

## Enterprise support

Commercial support is available at [acemq.com](https://acemq.com). It does not
change the licence on anything in this repository.
