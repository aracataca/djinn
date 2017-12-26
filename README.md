# Djinn

## Description

Djinn is a simple JavaFX application that monitors Jini registrars, and the
services known to them. It discovers registrars through Jini's
LookupDiscovery(..) class, which interprets Reggie broadcasts, and
displays them in a UI tree structure. It also displays a GUI accordion of the
groups to which registrars are sensible. Services known to registrars are
displayed by name under the tree node of the registrar they're known to.

## Workaround

Presently, Djinn collapses the registrar tree structure when a djinn mutates,
that is grows of shrinks. This is due to there appearing to be a bug in
JavaFX involving strange events that are emitted through the tree's
javafx.beans.value.ChangeListener, upon tree mutation.
