## Outcome

Describe the user-visible change.

## Verification

- [ ] Windows unit tests pass
- [ ] Python compilation check passes
- [ ] Firmware compiles for `arduino:avr:nano:cpu=atmega328old` when changed
- [ ] Simulator was tested for UI/protocol changes
- [ ] I stated whether real hardware moved during testing

## Safety and privacy

- [ ] Connecting still causes no movement
- [ ] Read-only diagnostics remain non-motion
- [ ] Motion commands remain guarded
- [ ] No credentials, camera frames, PGNs, Bluetooth addresses, or private paths are included

## Hardware documentation (when applicable)

- [ ] `./hardware/validate.ps1` passes
- [ ] Power, polarity, capacitor, diode, VREF, and connector changes are documented
- [ ] A first-power test was staged; motors and magnet were not connected all at once
- [ ] I stated whether the change was documentation-only, bench-tested, or motion-tested
- [ ] No incomplete PCB source is presented as fabrication-ready
