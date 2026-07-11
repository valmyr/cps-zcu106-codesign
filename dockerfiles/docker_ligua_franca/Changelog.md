# Changelog - Dockerfile (Lingua Franca fork `chisel3` / target Codesign)

Histórico das correções aplicadas à imagem, na ordem em que foram
introduzidas. Cada entrada referencia o item correspondente no
`README.md` (seção "LEIA ANTES DE USAR"), onde a explicação completa do
problema e da correção está detalhada.

## [Não versionado]

### Corrigido
- **Colisão de nome `csr` no `AXIPlatformWrapper.scala`.**
  A elaboração de hardware falhava com:
  `Attempted to name ZedBoardWrapper.csr: IO[AXILiteExternalIF] with a
  duplicated name 'csr'. Use suggestName to seed a unique name`.
  O `Wire` interno da lógica de regfile, antes chamado `csr`, foi
  renomeado para `csrSlave` (o IO externo `csr` foi mantido). A correção
  agora é aplicada às duas cópias do arquivo encontradas no repositório:
  - `core/src/main/resources/lib/chisel/.../platform-wrapper/axi/AXIPlatformWrapper.scala`
  - `core/build/resources/main/lib/chisel/.../platform-wrapper/axi/AXIPlatformWrapper.scala`

  Aproveitando a mesma edição, a construção da fila de write data trocou
  de `FPGAQueue(writeBurstAdp.out_writeData, 2) <> mem(i).writeData` para
  uma instância explícita `Module(new FPGAQueue(...))`.

  Ver README, item 8.

- **`chisel3`/`chiseltest` desalinhados entre os 4 arquivos que declaram
  a versão.**
  A troca de versão anterior (`3.5.3 → 3.6.0`, `0.5.1 → 0.6.0`) não
  cobriu o `fpga-tidbits/build.sbt` (que partia de `3.5.6`/`0.5.6`),
  causando o conflito binário `chisel3_2.13:3.6.0 ... is selected over
  3.5.6 for test` / `chiseltest_2.13:0.5.6 (depends on 3.5.6)`. A
  substituição passou a usar regex de patch-version
  (`3\.5\.[0-9]+` → `3.6.0`, `0\.5\.[0-9]+` → `0.6.0`) em vez de valores
  exatos, garantindo que os 4 arquivos fiquem na mesma dupla compatível.

  Ver README, item 2.

- **Submódulos apontando para SSH (`git@github.com:...`) sem chave
  disponível no container.**
  `.gitmodules` do `lingua-franca` corrigido via `sed` para HTTPS; o
  submódulo aninhado (`reactor-chisel` → `fpga-tidbits`) é coberto pelo
  `git config --global url."https://github.com/".insteadOf
  git@github.com:` global, sem precisar de um segundo `sed`.

  Ver README, item 3.

- **`.asUInt()` incompatível com a versão atualizada do Chisel** em
  `PlatformWrapper.scala` (`fpga-tidbits`), corrigido via `sed` para
  `.asUInt` (sem parênteses).

### Adicionado
- **Requisito de `fpgaBoard` no `target Codesign`.**
  Documentada a obrigatoriedade de declarar a plataforma alvo em todo
  `.lf` que usa `target Codesign` (ex.: `fpgaBoard: "PYNQZCU104CC"`),
  com a lista das plataformas suportadas pelo `fpga-tidbits` e o
  equivalente manual via `sbt "fpgatidbits/run verilog <acc> <platform>"`
  dentro de `src-gen/reactor-chisel`.

  Ver README, item 7.

- **Requisito de espaço em disco:** mínimo de 3 GB livres antes do
  `docker build`, sem contar o que o `sbt` baixa na primeira compilação
  de um target Codesign real (`chisel3`/`chiseltest`/Scala).

  Ver README, seção "Requisitos".
