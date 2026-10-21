# patches/ — as correções isoladas

Cada arquivo aqui é **exatamente** o conteúdo que `docker/Dockerfile` injeta na árvore do
compilador durante o build. Estão extraídos para cá para serem lidos, revisados e
versionados como código, não como string dentro de um `RUN cat >`.

## Onde cada um aterrissa

Raiz do clone: `/opt/lingua-franca`
Prefixo do template Chisel: `core/src/main/resources/lib/chisel/reactor-chisel/`

| Arquivo aqui | Destino no compilador |
|---|---|
| `reactor-chisel/Main.scala` | `<prefixo>/src/main/scala/Main.scala` |
| `fpga-tidbits/axi/AXIPlatformWrapper.scala` | `<prefixo>/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/axi/` |
| `fpga-tidbits/axi/PYNQZCU104CCWrapper.scala` | idem |
| `fpga-tidbits/axi/ZCU106Wrapper.scala` | idem (arquivo novo) |
| `fpga-tidbits/cpp/uiodriver.hpp` | `<prefixo>/fpga-tidbits/src/main/resources/cpp/platform-wrapper-regdriver/` (novo) |
| `fpga-tidbits/cpp/platform-mpsoc-uio.cpp` | idem (novo) |
| `fpga-tidbits/patch_PlatformWrapper.py` | edita `<prefixo>/fpga-tidbits/.../platform-wrapper/PlatformWrapper.scala` |
| `fpga-tidbits/patch_Main_platformMap.py` | edita `<prefixo>/fpga-tidbits/src/main/scala/fpgatidbits/Main.scala` |
| `compiler-kotlin/patch_CodesignGenerator.py` | edita `core/src/main/kotlin/org/lflang/generator/codesign/CodesignGenerator.kt` |
| `compiler-kotlin/patch_CodesignSwEmitter.py` | edita `core/src/main/kotlin/org/lflang/generator/codesign/CodesignSwEmitter.kt` |

Os `.py` editam por **âncora exata** com `assert content.count(anchor) == 1` — se o
upstream mudar a linha de referência, o build falha de forma ruidosa em vez de aplicar
silenciosamente um patch errado.

## Por que no template-fonte e não no projeto gerado

Cada chamada a `lfc arquivo.lf` recopia o template empacotado para `src-gen/reactor-chisel`,
apagando qualquer edição feita ali. Correção permanente só existe editando o template
e reempacotando:

```bash
cd /opt/lingua-franca && ./gradlew installDist
```

## Adicionar uma placa nova (o critério de generalização)

1. Copiar `fpga-tidbits/axi/ZCU106Wrapper.scala` → `NovaPlacaWrapper.scala`, trocar
   `platformName` e `suggestName`.
2. Duas linhas em `fpgatidbits/Main.scala`: `platformMap` e `fpgaPartMap`.
3. `./gradlew installDist`.

Nenhuma edição em `mainCodesign` — ele lê `platformMainDriverFile` e
`platformDriverExtraFlags` da própria instância do wrapper.
