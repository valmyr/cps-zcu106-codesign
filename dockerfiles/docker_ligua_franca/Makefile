VERMELHO = \033[1;31m
VERDE    = \033[1;32m
AMARELO  = \033[1;33m
RESET    = \033[0m
PROJETO  ?= loopback
ARCH ?= arm
TIMESTAMP := $(shell date +%Y%m%d_%H%M)

ifeq ($(ARCH),arm)
	TARGET_COMPILER := aarch64-linux-gnu-g++
	MODO := deploy
else ifeq ($(ARCH),x86_64)
	TARGET_COMPILER := g++
	MODO := simulacao
else
	$(error ARCH inválido: '$(ARCH)'. Use ARCH=arm ou ARCH=x86_64)
endif

.PHONY: all compiler deploy clean check-string

check-string:clean
	@if [ "$(MODO)" = "simulacao" ]; then \
		printf "$(VERDE)Modo simulacao detectado no arquivo!$(RESET)\n"; \
		printf "$(VERMELHO)Remova o target fpgaboard do projeto .lf$(RESET)\n"; \
		if [ "$(ARCH)" != "x86_64" ]; then \
			printf "$(VERMELHO)ERRO: arquivo indica modo simulacao, mas ARCH=$(ARCH). Use ARCH=x86_64$(RESET)\n"; \
			exit 1; \
		fi; \
	else \
		printf "$(VERMELHO)Modo implementacaoo em FPGA detectado!$(RESET)\n"; \
		if [ "$(ARCH)" != "arm" ]; then \
			printf "$(VERMELHO)ERRO: arquivo indica FPGA, mas ARCH=$(ARCH). Use ARCH=arm$(RESET)\n"; \
			exit 1; \
		fi; \
	fi

all: deploy

compiler: check-string
	@printf "$(VERDE)Compilando Reatores...$(RESET)\n"
	@printf "$(VERMELHO)lfc --target-compiler=$(TARGET_COMPILER) $(PROJETO)$(RESET)\n"
	lfc --target-compiler=$(TARGET_COMPILER) $(PROJETO)
	@printf "$(VERDE)Fim do processo$(RESET)\n"

deploy: compiler
	@if [ "$(MODO)" = "simulacao" ]; then \
		printf "$(AMARELO)Modo simulacao detectado deploy (empacotamento) nao sera executado.$(RESET)\n"; \
	else \
		printf "$(VERDE)Encapsulando implementacao Software e Hardware...$(RESET)\n"; \
		tar -czf ./$(basename $(PROJETO))_$(TIMESTAMP)_deploy.tar.gz \
			./codesign-gen/$(basename $(PROJETO))/src-gen/hardware/_FpgaTop/build/ZCU106Wrapper.v \
			./codesign-gen/$(basename $(PROJETO))/lib \
			./bin/$(basename $(PROJETO)) \
			./$(PROJETO);\
		printf "$(VERMELHO)Fim do encapsulamento$(RESET)\n"; \
	fi

clean:
	@printf "$(VERDE)Limpando artefatos...$(RESET)\n"
	@printf "$(VERMELHO)rm -rf codesign-gen$(RESET)\n"
	rm -rf codesign-gen
