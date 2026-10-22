# Makefile para compilar 
#Caso I deploy em FPGA: make all PROJETO=ExplorationMultipleReactorsCodesignAccelsOpBasics.lf ARCH=arm
#Caso II simulacao	  : make all PROJETO=ExplorationMultipleReactorsCodesignAccelsOpBasics.lf ARCH=x86_64
#É necessário especificar a extensão do arquivo; isso é uma escolha arbitrária exclusivamente minha.
VERMELHO = \033[1;31m
VERDE    = \033[1;32m
AMARELO  = \033[1;33m
RESET    = \033[0m
PROJETO  ?= ExplorationMultipleReactorsCodesignAccelsOpBasics.lf
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
		printf "$(VERDE)Modo simulação detectado no arquivo!$(RESET)\n"; \
		if [ "$(ARCH)" != "x86_64" ]; then \
			printf "$(VERMELHO)ERRO: arquivo indica modo simulação, mas ARCH=$(ARCH). Use ARCH=x86_64$(RESET)\n"; \
			exit 1; \
		fi; \
	else \
		printf "$(VERMELHO)Modo implementação em FPGA detectado!$(RESET)\n"; \
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
		printf "$(AMARELO)Modo simulação detectado — deploy (empacotamento) não será executado.$(RESET)\n"; \
	else \
		rm -rf ./deploy_all_files; \
		mkdir -p ./deploy_all_files; \
		rm -rf ./deploy_all_files/*; \
		mkdir -p deploy_all_files/bin;\
		printf "$(VERDE)Encapsulando implementacao Software e Hardware...$(RESET)\n"; \
		printf "$(VERDE)Copiando arquivos Verilog...$(RESET)\n"; \
		cp ./codesign-gen/$(basename $(PROJETO))/src-gen/hardware/_FpgaTop/build/ZCU106Wrapper.v ./deploy_all_files ; \
    cp ./codesign-gen/$(basename $(PROJETO))/src-gen/reactor-chisel/fpga-tidbits/src/main/resources/verilog/Q_srl.v ./deploy_all_files ; \
		printf "$(VERDE)Copiando arquivos da biblioteca...$(RESET)\n"; \
		cp -r ./codesign-gen/$(basename $(PROJETO))/lib ./deploy_all_files ; \
		cp -r ./bin/$(basename $(PROJETO)) ./deploy_all_files/bin/ ; \
		cp -r ./$(PROJETO) ./deploy_all_files/$(basename $(PROJETO))_SNAPSHOT_$(TIMESTAMP).lf ; \
		printf "$(VERDE)Compactando...$(RESET)\n"; \
		tar -czf ./$(basename $(PROJETO))_$(TIMESTAMP)_deploy.tar.gz \
			./deploy_all_files/ZCU106Wrapper.v \
      ./deploy_all_files/Q_srl.v \
			./deploy_all_files/lib \
			./deploy_all_files/bin/$(basename $(PROJETO)) \
			./deploy_all_files/$(basename $(PROJETO))_SNAPSHOT_$(TIMESTAMP).lf; \
		printf "$(VERMELHO)Fim do encapsulamento$(RESET)\n"; \
	fi
clean:
	@printf "$(VERDE)Limpando artefatos...$(RESET)\n"
	@printf "$(VERMELHO)rm -rf codesign-gen$(RESET)\n"
	rm -rf codesign-gen