# examples/
| File | fpgaBoard | Use |
|---|---|---|
| `loopback_sim.lf` | `VerilatedTester` | validates the reactor logic by simulation — `make compiler PROJECT=examples/loopback_sim.lf ARCH=x86_64` |
| `smoke4.lf` | `ZCU106` | the same loopback on real hardware; this is the Dockerfile's Smoke Test 4 |
| `ExplorationMultipleReactorsCodesignAccelsOpBasics.lf` | `ZCU106` | three chained accelerators (addition, multiplication, subtraction) in the PL + printing in the PS |

The `loopback_sim.lf` / `smoke4.lf` pair exists to show that **only the `fpgaBoard`
line changes** between simulating and going to the board — the rest of the source code
is identical.

Before any synthesis in Vivado, declare `clock-period` with the real period of the
clock that feeds the wrapper in the Block Design (e.g. `clock-period: 10 nsec` =
100 MHz).
