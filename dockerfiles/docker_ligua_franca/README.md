# Dockerfile - Ambiente de build para o Lingua Franca (fork `chisel3` / target Codesign)

> Histórico cronológico de todas as correções aplicadas à imagem: veja
> [`CHANGELOG.md`](./CHANGELOG.md).

## Requisitos

- **Espaço em disco: mínimo de 3 GB livres** antes de iniciar o `docker build`.
  Esse valor cobre a imagem base + JDK 17 + toolchain do gradle/sbt. Ele
  **não** inclui o que o sbt baixa na primeira compilação de um target
  Codesign real (`chisel3`/`chiseltest`/Scala e dependências transitivas -
  ver item 5 abaixo), então reserve espaço extra além desse mínimo,
  principalmente se for compilar vários testes Codesign diferentes (cada um
  gera sua própria pasta em `codesign-gen/`).
- Docker com suporte a `# syntax=docker/dockerfile:1` (heredocs em `RUN`).
- Acesso de rede durante o build (ver item 6 abaixo).

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
   (troca tempo de build por tempo de primeira execução); isso não foi
   feito aqui.

6) **Rede necessária durante o build:** GitHub (clone + submódulos), o
   repositório do sbt (`repo.scala-sbt.org`) e os espelhos do `apt` do
   Ubuntu. Maven Central / Coursier só entram em cena depois, no primeiro
   `lfc` real que gera hardware (item 5).

7) **É obrigatório especificar a plataforma alvo no target `Codesign`.**
   Todo `.lf` que usa `target Codesign` precisa declarar a propriedade
   `fpgaBoard`, indicando para qual placa/plataforma o hardware Chisel deve
   ser gerado:
   ```lf
   target Codesign {
       fpgaBoard: "PYNQZCU104CC"
   }
   ```
   `<platform>` é uma das opções suportadas pelo `fpga-tidbits`:
   - `VerilatedTester`
   - `GenericSDAccel`
   - `PYNQZ1`
   - `PYNQZCU104`
   - `ZC706`
   - `PYNQZCU104CC`
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

8) **`AXIPlatformWrapper.scala` é sobrescrito no build (fix do nome `csr`
   duplicado).**
   Sem essa correção, a elaboração de hardware falha com:
   `Attempted to name ZedBoardWrapper.csr: IO[AXILiteExternalIF] with a
   duplicated name 'csr'. Use suggestName to seed a unique name`. Isso
   acontece porque o arquivo original declara o `Wire` interno da lógica de
   regfile como `val csr = Wire(...)` - mesmo nome do IO externo
   `csr` (`extCsrIf.suggestName("csr")`) - e um wrapper concreto (ex.
   `ZedBoardWrapper`) acaba colidindo com esse nome na elaboração. O
   Dockerfile substitui o arquivo em
   `core/src/main/resources/lib/chisel/.../platform-wrapper/axi/AXIPlatformWrapper.scala`
   por uma versão corrigida que renomeia o `Wire` interno para `csrSlave`
   (mantendo o IO externo `csr` inalterado) e troca
   `FPGAQueue(writeBurstAdp.out_writeData, 2) <> mem(i).writeData` por uma
   instância explícita `Module(new FPGAQueue(...))`. O mesmo arquivo
   corrigido também é copiado para
   `core/build/resources/main/lib/chisel/.../platform-wrapper/axi/AXIPlatformWrapper.scala`
   (a segunda cópia listada no `find`, gerada pelo processamento de
   resources do gradle), garantindo que as duas fiquem idênticas mesmo se
   essa pasta já existir de um build/cache anterior. Esses passos rodam
   antes de `./gradlew assemble`, então já entram no `lfc` empacotado na
   imagem.

9) **SSH habilitado, porta 22 exposta.**
   O `openssh-server` (instalado desde o início do Dockerfile) agora é
   configurado e iniciado: host keys geradas com `ssh-keygen -A`,
   `PasswordAuthentication yes`, `PermitRootLogin no`, `/run/sshd`
   criado. Como o `CMD` roda como usuário `lingua-franca` (não root), o
   `sshd` é subido por um entrypoint (`/usr/local/bin/entrypoint.sh`) via
   `sudo /usr/sbin/sshd` antes de entregar o shell/`CMD` normalmente.
   Login por senha usa o mesmo usuário/senha do container:
   `lingua-franca` / `1234`. Para acessar, mapeie a porta ao rodar o
   container (ver "COMO USAR").

## COMO USAR
```bash
docker build -t lf-chisel .
docker run -d -p 2222:22 -v "$(pwd)/meus-lf:/workspace" --name lf lf-chisel
docker ps                              # confirma que o container continua "Up"
ssh -p 2222 lingua-franca@localhost    # senha: 1234

```
Dentro do container, `lfc <arquivo>.lf` já está no PATH. Os testes do fork
ficam em `/opt/lingua-franca/test/` (o target Codesign, especificamente, em
`/opt/lingua-franca/test/Codesign/src/` - `HelloCodesign.lf`, `VectorAdd.lf`,
etc.). Para usar seus próprios `.lf`, monte-os em `/workspace`, que é o
`WORKDIR` do container.

### Acessar via SSH
Mapeie a porta 22 do container para uma porta local (ex.: 2222) ao subir:
```bash
docker run -d -p 2222:22 -v "$(pwd)/meus-lf:/workspace" --name lf lf-chisel
ssh -p 2222 lingua-franca@localhost
# senha: 1234
```
Se preferir não deixar o container em background, `-it --rm` também
funciona normalmente - o `sshd` sobe do mesmo jeito antes do `CMD`.

ESPAÇO EM DISCO: não medimos o uso exato, mas conte com **no mínimo 3 GB**
para a imagem base + JDK 17 + toolchain do gradle/sbt, mais o que o sbt
baixar na primeira compilação de um target Codesign (item 5) -
`chisel3`/`chiseltest`/Scala trazem bastante coisa transitiva. Não é da
magnitude de um toolchain de vendor de FPGA (dezenas de GB), mas vale
reservar espaço de sobra, principalmente se for compilar vários testes
Codesign diferentes (cada um gera sua própria pasta em `codesign-gen/`).
