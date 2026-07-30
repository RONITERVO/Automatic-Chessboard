# Contributing

Contributions from first-time and experienced developers are welcome.

## Good first contributions

- Improve plain-language guidance or accessibility.
- Add tests for malformed protocol input.
- Improve simulator scenarios.
- Translate documentation or UI text.
- Test a different HC-08 revision, Nano clone, webcam, or Windows version.

## Pull requests

Keep changes focused, explain the user-visible outcome, and include tests when
behaviour changes. State whether real hardware moved during testing. Never attach
camera credentials, private video, PGNs, or an unreviewed support bundle.

Changes that can move hardware must document failure behaviour and must preserve:

- physical endstop authority;
- connection-without-motion behaviour;
- locked developer motion commands;
- local inspection after a motion fault;
- simulator and read-only diagnostic operation.

Run the unit tests and Python compilation check before opening a pull request.
