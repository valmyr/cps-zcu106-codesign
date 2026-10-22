# Changelog - Dockerfile (Lingua Franca fork `chisel3` / target Codesign)

> 🇧🇷 **Português** · [🇬🇧 English](CHANGELOG.md) — a versão em inglês é a canônica.

<!-- translated-from: CHANGELOG.md@0ad06220c587dbc56eca15e4552068f564aec71c -->

Histórico das correções aplicadas à imagem, na ordem em que foram
introduzidas. Cada entrada referencia o item correspondente no
`README.md` (seção "LEIA ANTES DE USAR"), onde a explicação completa do
problema e da correção está detalhada.

## [Não versionado]

### Corrigido
- **`PYNQZCU104CCWrapper` nunca copiava os arquivos de driver de fato.**
  O wrapper só *definia* `platformDriverFiles`, sem chamar
  `resourceCopyBulk`/`generateRegDriver` - `build/` nunca continha
  `platform.h`/`CodesignTopReactor.hpp`/o driver para essa plataforma.
  Corrigido; também passou a sobrescrever `platformDriverExtraFlags` com
  `-fpermissive` (necessário porque `linuxphysregdriver.hpp` foi escrito
  para ARM 32 bits e faz cast de `void*` para `unsigned int`, rejeitado
  por padrão em ARM64).

  Ver README, item 8.1.

- **Driver `xlnk` inexistente em kernels `linux-xlnx` modernos.**
  Confirmado lendo o próprio código-fonte do driver no BSP de uma ZCU106
  real (PetaLinux 2025.2): a `of_match_table` do `xlnk` está vazia por
  padrão, o dispositivo nunca é criado. Substituído por um driver
  canônico baseado no subsistema UIO do kernel
  (`uio_pdrv_genirq` + `mmap` de `/dev/uioN`), sem depender de nenhuma lib
  proprietária da Xilinx - `uiodriver.hpp` / `platform-mpsoc-uio.cpp`,
  novos.

  Ver README, item 8.2.

- **`mainCodesign` com o driver a compilar *hardcoded* só para
  `VerilatedTester`.**
  Generalizado: `PlatformWrapper.scala` ganhou os campos
  `platformMainDriverFile` (deduzido automaticamente) e
  `platformDriverExtraFlags` (default vazio), com defaults seguros que
  não quebram os wrappers pré-existentes que não os sobrescrevem.
  `mainCodesign` (`reactor-chisel/src/main/scala/Main.scala`) agora lê
  esses campos da instância do wrapper em vez de ter nome de arquivo/flags
  fixos no código - qualquer placa real nova passa a funcionar sem editar
  esse arquivo de novo. Validado criando uma placa fictícia (`TESTBOARD`)
  tocando em um único arquivo novo + duas linhas de registro.

  Corrigido também, no meio-tempo: instanciar o wrapper fora do bloco
  passado por nome ao `emitVerilog` falhava com
  `java.lang.IllegalArgumentException: requirement failed: must be inside
  Builder context` (elaboração de `Module` só é permitida dentro do
  contexto do Chisel Builder) - resolvido capturando a instância numa
  `var` externa, atribuída dentro do próprio bloco por nome.

  Ver README, item 8.3.

- **Software nunca era compilado automaticamente para placas reais (só
  para `VerilatedTester`).**
  `CodesignGenerator.kt`: a condição `if
  (targetConfig.fpgaBoard.equals("VerilatedTester"))` que controlava a
  chamada de `swEmitter.compile()` virou incondicional (`if (true)`).

  Ver README, item 9.1.

- **`--target-compiler` não chegava à segunda passada de geração interna
  do software, mesmo com a correção acima.**
  `CodesignSwEmitter.compile()` cria um `MainContext`/`TargetConfig` novo
  internamente, com `Properties()` vazio como argumentos de linha de
  comando - o `--target-compiler` original nunca chegava até essa segunda
  geração, fazendo a compilação automática sempre cair no compilador
  nativo x86 e falhar no link com `lfFPGA.a` ARM64
  (`Relocations in generic ELF (EM: 183)` /
  `error adding symbols: file in wrong format`). Corrigido em
  `generateTarget()` (`CodesignSwEmitter.kt`): passou a escrever
  `compiler: "<valor>"` no `_SwTop.lf` gerado quando
  `targetConfig.compiler` não está vazio, propagando o compilador
  corretamente através da segunda geração.

  Ver README, item 9.2.

- **Pacote `file` ausente na imagem base.**
  Necessário tanto para os smoke tests (checar arquitetura do binário
  gerado) quanto para uso manual geral; adicionado à lista de pacotes
  `apt-get install`.

  Ver README, item 11.

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
- **Suporte nativo à placa `ZCU106`** (`fpgaBoard: ZCU106`), com wrapper
  próprio (`ZCU106Wrapper.scala`, mesmo chip da PYNQZCU104CC,
  `xczu7ev-ffvc1156-2-e`), registrado em `platformMap`/`fpgaPartMap`
  (`fpgatidbits/Main.scala`). Compila de forma limpa, sem warnings
  (diferente de `PYNQZCU104CC`, que usa o driver legado de 32 bits com
  `-fpermissive`).

  Ver README, item 8.

- **Quatro smoke tests embutidos no `docker build`**, validando: lógica
  isolada do `UioDriver` sem hardware; fluxo de simulação
  (`VerilatedTester`) de ponta a ponta; geração de `lfFPGA.a` para
  `ZCU106`; compilação automática do software para `ZCU106` com um único
  comando. Falha em qualquer um deles impede a imagem de ser construída,
  em vez de só aparecer na primeira vez que alguém usar o container.

  Ver README, seção "Smoke tests embutidos".

- **Tema do Oh My Zsh (`jonathan`)**, configurado via `sed` no
  `~/.zshrc` logo após a instalação. Puramente cosmético.

  Ver README, item 13.

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

### Conhecido (não corrigido nesta imagem)
- **`internal compiler error: Segmentation fault` do GCC 11**, ao
  compilar `main.cc` (gerado automaticamente pelo framework, usa
  `std::regex` internamente) em cross-compilação para AArch64. Ocorreu de
  forma intermitente durante o desenvolvimento; memória insuficiente
  descartada como causa (62 GB disponíveis no ambiente onde ocorreu).
  Hipóteses ainda não confirmadas: bug do GCC 11 específico desse padrão
  de template; sensibilidade a nível de otimização.

  Ver README, item 10.

- **Reset do `ZCU106Wrapper` precisa ser conectado manualmente à saída
  ativa-em-alto do `proc_sys_reset` no Block Design do Vivado**
  (`peripheral_reset`, não `peripheral_aresetn`) - fora do escopo deste
  Dockerfile/compilador, documentado no backlog de bring-up da placa
  física.

- **Bug em investigação na coordenação de tempo lógico**:
  `logicalTagComplete` trava em `Tag.FOREVER` (`0x80000000`) quando o
  tempo lógico acumulado de execução ultrapassa ~2,15 segundos (`2^31`
  ns) - suspeita de comparação com sinal indevida em algum ponto da
  lógica gerada em Chisel, sem correção confirmada ainda.

  Ver README, "Pendências conhecidas".
