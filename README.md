# Djinn

## Description

Djinn is a simple JavaFX application that monitors Jini registrars, and the
services known to them. It discovers registrars through Jini's
LookupDiscovery(..) class and displays them in a UI tree structure. It also
displays a GUI accordion of the groups to which registrars are sensible.
Services known to registrars are displayed by name under the tree node of
the registrar they're known to.

## Running

Djinn will run on Java 7 & 8. It should run on Java 9, though hasn't been tested. It requires the
* -Djava.security.manager
* -Djava.security.policy=all.policy

command line switches.
