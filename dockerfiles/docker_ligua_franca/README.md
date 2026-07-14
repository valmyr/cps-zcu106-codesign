# Dockerfile - Ambiente de build para o Lingua Franca (fork `chisel3` / target Codesign)

> Histórico cronológico de todas as correções aplicadas à imagem: veja
> [`CHANGELOG.md`](./CHANGELOG.md).

## Requisitos

- **Espaço em disco: mínimo de 15 GB livres** antes de iniciar o `docker build`.
  Esse valor cobre a imagem base + JDK 17 + toolchain do gradle/sbt. Ele
  **não** inclui o que o sbt baixa na primeira compilação de um target
  Codesign real (`chisel3`/`chiseltest`/Scala e dependências transitivas -
  ver item 5 abaixo), então reserve espaço extra além desse mínimo,
  principalmente se for compilar vários testes Codesign diferentes (cada um
  gera sua própria pasta em `codesign-gen/`).
- Docker com suporte a `# syntax=docker/dockerfile:1` (heredocs em `RUN`).
- Acesso de rede durante o build (ver item 6 abaixo).
- **Memória recomendada para o `docker build`: pelo menos 8 GB livres.**
  Não é bloqueador na maioria dos casos (62 GB totais foram suficientes em
  testes), mas a compilação de `main.cc` (gerado automaticamente pelo
  framework, usa `std::regex` internamente) já disparou um *internal
  compiler error* do GCC 11 em cross-compilação para AArch64 sob certas
  condições - ver item 10 (Problemas conhecidos) antes de assumir que é
  falta de memória.

## LEIA ANTES DE USAR

1) **Fork usado, não o lf-lang oficial.**
   Este Dockerfile clona `erlingrj/lingua-franca` na branch `chisel3`, um fork
   de pesquisa que adiciona o target `Codesign`: gera hardware (Chisel → FPGA,
   via os submódulos `reactor-chisel` e `fpga-tidbits`) e software (C++) a
   partir do mesmo `.lf`. Não é o compilador `lf-lang/lingua-franca`
   "mainline" - se você só precisa dos targets padrão (C, C++, Python, Rust,
   TypeScript), essa não é a imagem certa.

2) **`chisel3` e `chiseltest` têm que andar sempre na mesma dupla (3.6.x com
   0.6.x).**
   O repo declara essas duas versões em 4 arquivos diferentes
   (`ChiselSbtGenerator.kt`, `reactor-chisel/build.sbt`,
   `reactor-chisel/build.sc`, `fpga-tidbits/build.sbt`), cada um começando de
   uma patch-version distinta. Se um dia for subir a versão do Chisel de
   novo, atualize as 4 ocorrências e confira a tabela oficial de
   compatibilidade (chisel-lang.org/docs/appendix/versioning) antes de trocar
   os números. Migrar só parte delas foi exatamente o que causou, aqui, o
   erro `chisel3_2.13:3.6.0 ... is selected over 3.5.6` /
   `chiseltest_2.13:0.5.6 (depends on 3.5.6)` - e ele só aparece quando
   alguém roda um `.lf` do target Codesign de verdade (não em `lfc --help`),
   então é fácil publicar a imagem sem perceber que está quebrada.

3) **Submódulos usam SSH por padrão no upstream.**
   Tanto o `.gitmodules` do `lingua-franca` (aponta pro `reactor-chisel`)
   quanto o do próprio `reactor-chisel` (aponta pro `fpga-tidbits`, aninhado)
   usam `git@github.com:...`. Sem chave SSH dentro do container isso falha
   na hora do `git submodule update`. O Dockerfile reescreve para HTTPS via
   `sed` no `.gitmodules` + `git config --global
   url."https://github.com/".insteadOf git@github.com:` (esse config global
   é o que cobre o submódulo aninhado, sem precisar de mais um `sed`). Se o
   upstream adicionar outro submódulo, confira se ele também usa SSH.

4) **O container roda como usuário `lingua-franca`, não root.**
   Ele é dono de `/opt/lingua-franca` - precisa, porque é ali que o `lfc`
   escreve o código gerado (`test/Codesign/codesign-gen/...`). Pra rodar
   algo como root dentro de um container já iniciado:
   `docker exec -u root -it <container> bash`.

5) **O cache copiado pro usuário não inclui `chisel3`/`chiseltest`.**
   O build da imagem só compila o `lfc` em si (`./gradlew assemble`) e chama
   `lfc --help` - nenhum dos dois toca o projeto sbt do Chisel. Ou seja: a
   primeira vez que alguém rodar `lfc <arquivo>.lf` num target Codesign de
   verdade dentro de um container, o sbt ainda baixa `chisel3`/`chiseltest`/o
   compilador Scala do zero (minutos, dependendo da rede) - uma vez por
   container criado com `docker run`, não uma vez por comando. Pra eliminar
   essa espera seria preciso rodar um teste Codesign real *durante* o build
   (troca tempo de build por tempo de primeira execução); os smoke tests
   4 (item 8) já fazem exatamente isso para `ZCU106`, então containers
   construídos a partir deste Dockerfile já saem com o cache do sbt
   "aquecido" para essa placa especificamente.

6) **Rede necessária durante o build:** GitHub (clone + submódulos), o
   repositório do sbt (`repo.scala-sbt.org`) e os espelhos do `apt` do
   Ubuntu. Maven Central / Coursier entram em cena a partir do primeiro
   `sbt run`/`lfc` real que gera hardware (item 5), incluindo durante os
   smoke tests deste próprio Dockerfile (item 8).

7) **É obrigatório especificar a plataforma alvo no target `Codesign`.**
   Todo `.lf` que usa `target Codesign` precisa declarar a propriedade
   `fpgaBoard`, indicando para qual placa/plataforma o hardware Chisel deve
   ser gerado:
   ```lf
   target Codesign {
       fpgaBoard: ZCU106
   }
   ```
   `fpgaBoard` é uma das opções suportadas pelo `fpga-tidbits`:
   - `VerilatedTester` (simulação - único valor para o qual o `lfc` original
     compilava o software automaticamente; ver item 8 sobre a extensão dessa
     automação para qualquer placa)
   - `GenericSDAccel`
   - `PYNQZ1`
   - `PYNQZCU104`
   - `ZC706`
   - `PYNQZCU104CC`
   - **`ZCU106`** *(adicionado neste fork do Dockerfile - ver item 8)*
   - `ZedBoard`
   - `WX690T`
   - `PYNQU96CC`
   - `Tester`
   - `PYNQU96`

   Essa mesma plataforma é o que se passa manualmente para o `sbt` do
   `fpga-tidbits` ao gerar/rodar o hardware fora do fluxo do `lfc`, dentro
   de `src-gen/reactor-chisel` do projeto gerado:
   ```bash
   cd Projeto/src-gen/reactor-chisel
   sbt "fpgatidbits/run verilog HelloAccel PYNQZCU104CC"
   sbt "fpgatidbits/run"
   ```
   Sem `fpgaBoard` definido no target (ou passado ao `sbt`), a geração do
   hardware não sabe para qual placa compilar e falha.

8) **`ZCU106` - suporte nativo completo (driver, geração de `lfFPGA.a`,
   compilação automática de software).**

   A ZCU106 usa o mesmo chip da ZCU104 (`xczu7ev-ffvc1156-2-e`), mas o fork
   original não tinha um wrapper com esse nome nem um driver que funcionasse
   fora do ecossistema PYNQ. Três problemas foram corrigidos, em cadeia:

   **8.1) `PYNQZCU104CCWrapper` nunca copiava os arquivos de driver de
   verdade.** O wrapper original só *definia* `platformDriverFiles`, sem
   nunca chamar `resourceCopyBulk`/`generateRegDriver` - por isso `build/`
   nunca continha `platform.h`/`CodesignTopReactor.hpp`/o driver em si para
   essa plataforma. Corrigido (também sobrescreve `platformDriverExtraFlags`
   com `-fpermissive`, necessário porque `linuxphysregdriver.hpp` foi escrito
   para ARM 32 bits e faz cast de `void*` para `unsigned int`, rejeitado por
   padrão em ARM64).

   **8.2) `xlnk` (driver de kernel usado pelo PYNQ) não existe em kernels
   `linux-xlnx` modernos.** Confirmado lendo o próprio código-fonte do
   driver no BSP de uma ZCU106 real rodando PetaLinux 2025.2 - a
   `of_match_table` do `xlnk` está vazia por padrão e o dispositivo nunca é
   criado. Em vez de depender de `xlnk`, foi escrito um driver canônico
   (`uiodriver.hpp` / `platform-mpsoc-uio.cpp`) baseado no subsistema **UIO**
   do kernel (`uio_pdrv_genirq` + `mmap` de `/dev/uioN`), que é o mecanismo
   suportado nativamente por qualquer kernel Linux moderno, sem depender de
   nenhuma lib proprietária da Xilinx. Nome de dispositivo configurável via
   macro (`LF_FPGA_UIO_NAME`, default `"lf_fpga_regs"`) e via argumento do
   construtor - em bring-up real numa ZCU106, o nome que o `sdtgen`/PetaLinux
   de fato expõe em `/sys/class/uio/uioN/name` foi `"ZCU106Wrapper@a0000000"`,
   não `"lf_fpga_regs"`, então `platform-mpsoc-uio.cpp` passa
   `"ZCU106Wrapper"` como hint explícito no construtor (a busca é por
   substring, não precisa do endereço completo).

   **8.3) `mainCodesign` tinha o driver a compilar *hardcoded* só para
   `VerilatedTester`.** Generalizado: a classe base `PlatformWrapper.scala`
   ganhou dois campos com defaults seguros -
   `platformMainDriverFile` (deduzido automaticamente do primeiro `.cpp` em
   `platformDriverFiles`) e `platformDriverExtraFlags` (vazio por padrão,
   sobrescrito onde necessário, como em 8.1). `mainCodesign`
   (`reactor-chisel/src/main/scala/Main.scala`) agora lê esses dois campos
   da própria instância do wrapper, em vez de ter o nome do arquivo/flags
   fixos no código - qualquer placa nova (real, não `VerilatedTester`) passa
   a funcionar sem editar esse arquivo de novo, só criando o wrapper e
   registrando em `platformMap`/`fpgaPartMap` (`fpgatidbits/Main.scala`).
   Validado criando uma placa fictícia (`TESTBOARD`, mesmo chip, mesmo
   driver) tocando em um único arquivo novo + duas linhas de registro, sem
   nenhuma edição em `mainCodesign`.

   *Armadilha de implementação encontrada nesse meio-tempo:* instanciar o
   wrapper **fora** do bloco passado por nome ao `emitVerilog` falha com
   `java.lang.IllegalArgumentException: requirement failed: must be inside
   Builder context`, porque `PlatformWrapper` estende `Module` do Chisel e a
   elaboração só é permitida dentro do contexto do Builder que o próprio
   `emitVerilog` abre. Solução: capturar a instância numa `var` externa,
   atribuída **dentro** do bloco por nome.

   **Resultado prático**: `fpgaBoard: ZCU106` gera `lfFPGA.a` com o driver
   `UioDriver` (`platform-mpsoc-uio.o`), compilação limpa sem warnings
   (diferente de `PYNQZCU104CC`, que ainda usa o driver legado de 32 bits
   com `-fpermissive`) - ver Smoke Test 4 no Dockerfile, que valida isso a
   cada build da imagem.

9) **Compilação automática do software para qualquer placa (não só
   `VerilatedTester`) + propagação correta do `--target-compiler`.**

   Duas correções em Kotlin, complementares:

   **9.1) `CodesignGenerator.kt`** originalmente só chamava
   `swEmitter.compile()` (e copiava o binário para `bin/`) quando
   `fpgaBoard.equals("VerilatedTester")` - para qualquer placa real, o
   código-fonte do software era gerado mas nunca compilado, exigindo um
   segundo comando manual (`lfc --target-compiler aarch64-linux-gnu-g++
   _SwTop.lf`, dentro de `src/software/`). Patch: a condição virou
   incondicional (`if (true)`), então a compilação automática passa a
   acontecer para qualquer `fpgaBoard`.

   **9.2) Isso sozinho não bastava.** `CodesignSwEmitter.compile()` cria
   internamente um `MainContext`/`TargetConfig` **novo**, usando
   `Properties()` vazio como argumentos de linha de comando - ou seja, o
   `--target-compiler` passado ao `lfc` original nunca chega até essa
   segunda passada de geração interna. O `TargetConfig` reconstruído só
   reflete o que está *escrito no próprio `.lf`* gerado (via
   `TargetProperty.COMPILER`, keyword `compiler` no target `Cpp`). Sem essa
   segunda correção, a compilação automática (9.1) sempre cai no compilador
   nativo do sistema (`g++` x86), gerando erros de linker do tipo
   `Relocations in generic ELF (EM: 183)` / `error adding symbols: file in
   wrong format` ao tentar linkar com um `lfFPGA.a` ARM64. Patch:
   `generateTarget()` (`CodesignSwEmitter.kt`) agora escreve
   `compiler: "<valor>"` no `_SwTop.lf` gerado quando
   `targetConfig.compiler` não está vazio - o compilador certo passa a ser
   propagado corretamente através da segunda geração interna.

   **Resultado prático**: `lfc --target-compiler aarch64-linux-gnu-g++
   arquivo.lf` sozinho já gera **e compila** hardware + software, sem
   nenhum comando manual adicional - para qualquer `fpgaBoard`, não só
   `VerilatedTester`.

10) **Problema conhecido, ainda em investigação: `internal compiler error:
    Segmentation fault` do GCC 11 ao compilar `main.cc` (código gerado
    automaticamente pelo framework, usa `std::regex` internamente) em
    cross-compilação para AArch64.**
    Ocorreu de forma intermitente durante o desenvolvimento deste
    Dockerfile, na função `_M_word_boundary` da implementação de
    `std::regex` da libstdc++, durante a passagem de otimização `cddce`.
    Memória insuficiente foi descartada como causa (62 GB disponíveis no
    ambiente onde ocorreu). Hipóteses ainda não confirmadas: bug específico
    do GCC 11 (Ubuntu 22.04) com esse padrão de template em
    cross-compilação; sensibilidade a nível de otimização (`-O2`, padrão do
    CMake `Release`, vs. `-O1`/`-O0`). Se o Smoke Test 4 falhar com essa
    mensagem específica, tente reduzir a otimização
    (`ENV CXXFLAGS="-O1"` antes do `./gradlew assemble`) ou considere
    instalar uma versão mais recente do `g++-aarch64-linux-gnu` via PPA,
    já que o Ubuntu 22.04 só traz GCC 11 nos repositórios oficiais.

11) **Pacote `file` incluído explicitamente.**
    Não vem por padrão nesta imagem base minimalista, apesar de parecer um
    utilitário Unix básico - necessário tanto para o Smoke Test 4 (checar
    a arquitetura do binário gerado) quanto para uso manual geral (é a
    ferramenta padrão usada ao longo de todo o fluxo de bring-up em placa
    física para confirmar `ELF ... ARM aarch64` nos binários cross-compilados).

12) **SSH habilitado, porta 22 exposta.**
    O `openssh-server` (instalado desde o início do Dockerfile) agora é
    configurado e iniciado: host keys geradas com `ssh-keygen -A`,
    `PasswordAuthentication yes`, `PermitRootLogin no`, `/run/sshd`
    criado. Como o `CMD` roda como usuário `lingua-franca` (não root), o
    `sshd` é subido por um entrypoint (`/usr/local/bin/entrypoint.sh`) via
    `sudo /usr/sbin/sshd` antes de entregar o shell/`CMD` normalmente.
    Login por senha usa o mesmo usuário/senha do container:
    `lingua-franca` / `1234`. Para acessar, mapeie a porta ao rodar o
    container (ver "COMO USAR").

13) **Tema do Oh My Zsh: `jonathan`.**
    Configurado via `sed` no `~/.zshrc` logo após a instalação do Oh My Zsh,
    substituindo o tema padrão (`robbyrussell`). Puramente cosmético, não
    afeta nenhum fluxo de build/geração - documentado aqui só para quem for
    customizar de novo depois saber onde procurar (`ZSH_THEME=` no
    `~/.zshrc`, um único `sed -i 's/^ZSH_THEME=.*/ZSH_THEME="jonathan"/'
    ~/.zshrc`).

## Smoke tests embutidos no build

Este Dockerfile roda quatro testes funcionais **durante** o `docker build`
(não depois) - se algum falhar, a imagem inteira falha ao construir, em vez
de você só descobrir o problema na primeira vez que usar o container:

| # | O que valida |
|---|---|
| 1 | Lógica isolada do `UioDriver` (round-trip de registradores, busca de dispositivo por nome), sem hardware real, usando uma árvore de diretório falsa no lugar de `/sys/class/uio`. |
| 2 | Fluxo de simulação (`VerilatedTester`) de ponta a ponta: gera hardware+software, executa o binário, confirma a assinatura esperada do `CodesignTopReactor` no output. |
| 3 | Geração de `lfFPGA.a` para hardware real (`ZCU106`), confirmando `platform-mpsoc-uio.o` presente no arquivo, sem warnings de compilação. |
| 4 | **Compilação automática do software** para `ZCU106` com um único comando (`lfc --target-compiler aarch64-linux-gnu-g++ arquivo.lf`, sem segundo passo manual), confirmando `ELF ... ARM aarch64` no binário final. |

## COMO USAR
```bash
docker build -t lf-chisel .
docker run -it --rm -v "$(pwd)/meus-lf:/workspace" lf-chisel
```
Dentro do container, `lfc <arquivo>.lf` já está no PATH. Os testes do fork
ficam em `/opt/lingua-franca/test/` (o target Codesign, especificamente, em
`/opt/lingua-franca/test/Codesign/src/` - `HelloCodesign.lf`, `VectorAdd.lf`,
etc.). Para usar seus próprios `.lf`, monte-os em `/workspace`, que é o
`WORKDIR` do container.

**Atenção**: se você passar um comando explícito ao `docker run` (ex.:
`docker run -it lf-chisel bash`), isso sobrescreve o `CMD ["/bin/zsh"]` da
imagem - para entrar direto no zsh (com o tema `jonathan`), rode
`docker run -it --rm lf-chisel` sem especificar comando nenhum no final.

### Gerando hardware + software para a ZCU106 com um único comando
```bash
cat > meu_teste.lf << 'EOF'
target Codesign {
  fpgaBoard: ZCU106,
  clock-period: 10 nsec
}
reactor Fpga {
  input in: {= UInt(32.W) =}
  output out: {= UInt(32.W) =}
  reaction(in) -> out {= lf_set(out, lf_get(in)) =}
}
reactor Sw {
  input in: uint32_t
  output out: uint32_t
  reaction(startup) -> out {= out.set(1); =}
  reaction(in) {= std::cout << "Got: " << *in.get() << std::endl; =}
}
main reactor {
  @fpga
  fpga = new Fpga()
  sw = new Sw()
  sw.out, fpga.out -> fpga.in, sw.in
}
EOF
lfc --target-compiler aarch64-linux-gnu-g++ meu_teste.lf
file codesign-gen/meu_teste/bin/_SwTop
```
Deve retornar `ELF 64-bit ... ARM aarch64` sem nenhum comando adicional
(ver item 9 acima).

### Acessar via SSH
Mapeie a porta 22 do container para uma porta local (ex.: 2222) ao subir:
```bash
docker build -t lf-chisel .
docker run -d -p 2222:22 -v "$(pwd)/meus-lf:/workspace" --name lf lf-chisel
docker ps                              # confirma que o container continua "Up"
ssh -p 2222 lingua-franca@localhost    # senha: 1234
```
Se preferir não deixar o container em background, `-it --rm` também
funciona normalmente - o `sshd` sobe do mesmo jeito antes do `CMD`.

ESPAÇO EM DISCO: não medimos o uso exato, mas conte com **no mínimo 15 GB**
para a imagem base + JDK 17 + toolchain do gradle/sbt, mais o que o sbt
baixar na primeira compilação de um target Codesign (item 5) -
`chisel3`/`chiseltest`/Scala trazem bastante coisa transitiva. Não é da
magnitude de um toolchain de vendor de FPGA (dezenas de GB), mas vale
reservar espaço de sobra, principalmente se for compilar vários testes
Codesign diferentes (cada um gera sua própria pasta em `codesign-gen/`).

## Pendências conhecidas (fora do escopo deste Dockerfile)

Estas correções vivem em outros artefatos/projetos (Vivado, PetaLinux), não
no `lfc`/nesta imagem, mas são pré-requisitos reais para rodar em hardware
físico:

- **Reset do `ZCU106Wrapper` precisa ser conectado à saída ativa-em-alto do
  `proc_sys_reset` (`peripheral_reset`), não à ativa-em-baixo
  (`peripheral_aresetn`)** - erro de conexão manual no Block Design do
  Vivado, não algo que o `lfc` gera ou controla.
- **Bug em investigação no `TriggerGenerator`/coordenação de tempo lógico**:
  `logicalTagComplete` trava no valor sentinela `Tag.FOREVER` (`0x80000000`)
  quando o tempo lógico acumulado de execução ultrapassa ~2,15 segundos
  (`2^31` ns) - suspeita de comparação com sinal (`signed`) indevida em
  algum ponto da lógica gerada em Chisel, ainda sem correção confirmada no
  código-fonte.
- **Overlay de device tree + bootarg no PetaLinux**
  (`compatible = "generic-uio"` + `uio_pdrv_genirq.of_id=generic-uio"`) -
  necessário para o kernel criar `/dev/uioN` de fato; documentado
  separadamente no backlog de bring-up da placa física.
