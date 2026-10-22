# examples/

> 🇧🇷 **Português** · [🇬🇧 English](README.md) — a versão em inglês é a canônica.

<!-- translated-from: examples/README.md@f800594874f32cb2b225e0293e286872822d3d3e -->

| Arquivo | fpgaBoard | Uso |
|---|---|---|
| `loopback_sim.lf` | `VerilatedTester` | valida a lógica do reactor por simulação — `make compiler PROJETO=examples/loopback_sim.lf ARCH=x86_64` |
| `smoke4.lf` | `ZCU106` | o mesmo loopback em hardware real; é o Smoke Test 4 do Dockerfile |
| `ExplorationMultipleReactorsCodesignAccelsOpBasics.lf` | `ZCU106` | três aceleradores encadeados (soma, multiplicação, subtração) na PL + impressão no PS |

O par `loopback_sim.lf` / `smoke4.lf` existe para mostrar que **só a linha `fpgaBoard` muda**
entre simular e ir para a placa — o resto do código-fonte é idêntico.

Antes de qualquer síntese no Vivado, declare `clock-period` com o período real do clock
que alimenta o wrapper no Block Design (ex.: `clock-period: 10 nsec` = 100 MHz).
