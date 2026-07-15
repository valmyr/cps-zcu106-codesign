VERMELHO = \033[1;31m
VERDE    = \033[1;32m
AMARELO  = \033[1;33m
RESET    = \033[0m
PROJETO  ?= loopback
ARCH ?= arm
TIMESTAMP := $(shell date +%Y%m%d_%H%M)

ifeq ($(ARCH),arm)
    TARGET_COMPILER := aarch64-linux-gnu-g++
else ifeq ($(ARCH),x86_64)
    TARGET_COMPILER := g++
else
    $(error ARCH inválido: '$(ARCH)'. Use ARCH=arm ou ARCH=x86_64)
endif

.PHONY: all compiler deploy clean
all: deploy
compiler:
	@printf "$(VERDE)Compilando Reatores...$(RESET)\n"
	@printf "$(VERMELHO)lfc --target-compiler=$(TARGET_COMPILER) $(PROJETO)$(RESET)\n"
	lfc --target-compiler=$(TARGET_COMPILER) $(PROJETO)
	@printf "$(VERDE)Fim do processo$(RESET)\n"
deploy: compiler
	@printf "$(VERDE)Encapsulando implementacao Software e Hardware...$(RESET)\n"
	tar -czf ./$(basename $(PROJETO))_$(TIMESTAMP)_deploy.tar.gz \
		./codesign-gen/$(basename $(PROJETO))/src-gen/hardware/_FpgaTop/build/ZCU106Wrapper.v \
		./codesign-gen/$(basename $(PROJETO))/lib \
		./bin/$(basename $(PROJETO))
	@printf "$(VERMELHO)Fim do encapsulamento$(RESET)\n"
clean:
	@printf "$(VERDE)Limpando artefatos...$(RESET)\n"
	@printf "$(VERMELHO)rm -rf codesign-gen$(RESET)\n"
	rm -rf codesign-gen
