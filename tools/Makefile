# Makefile for compiling
#Case I  FPGA deploy : make all PROJECT=ExplorationMultipleReactorsCodesignAccelsOpBasics.lf ARCH=arm
#Case II simulation  : make all PROJECT=ExplorationMultipleReactorsCodesignAccelsOpBasics.lf ARCH=x86_64
#The file extension must be given explicitly; that is an arbitrary choice of mine alone.
RED      = \033[1;31m
GREEN    = \033[1;32m
YELLOW   = \033[1;33m
RESET    = \033[0m
PROJECT  ?= ExplorationMultipleReactorsCodesignAccelsOpBasics.lf
# Backwards compatibility: PROJETO was the former name of PROJECT and is
# still accepted. A PROJECT passed on the command line always wins.
ifdef PROJETO
PROJECT := $(PROJETO)
endif
ARCH ?= arm
TIMESTAMP := $(shell date +%Y%m%d_%H%M)
ifeq ($(ARCH),arm)
    TARGET_COMPILER := aarch64-linux-gnu-g++
    MODE := deploy
else ifeq ($(ARCH),x86_64)
    TARGET_COMPILER := g++
    MODE := simulation
else
    $(error invalid ARCH: '$(ARCH)'. Use ARCH=arm or ARCH=x86_64)
endif
.PHONY: all compiler deploy clean check-string
check-string:clean
	@if [ -n "$(PROJETO)" ]; then \
		printf "$(YELLOW)WARNING: PROJETO is deprecated, use PROJECT instead.$(RESET)\n"; \
	fi
	@if [ "$(MODE)" = "simulation" ]; then \
		printf "$(GREEN)Simulation mode detected in the file!$(RESET)\n"; \
		if [ "$(ARCH)" != "x86_64" ]; then \
			printf "$(RED)ERROR: file indicates simulation mode, but ARCH=$(ARCH). Use ARCH=x86_64$(RESET)\n"; \
			exit 1; \
		fi; \
	else \
		printf "$(RED)FPGA implementation mode detected!$(RESET)\n"; \
		if [ "$(ARCH)" != "arm" ]; then \
			printf "$(RED)ERROR: file indicates FPGA, but ARCH=$(ARCH). Use ARCH=arm$(RESET)\n"; \
			exit 1; \
		fi; \
	fi
all: deploy
compiler: check-string
	@printf "$(GREEN)Compiling reactors...$(RESET)\n"
	@printf "$(RED)lfc --target-compiler=$(TARGET_COMPILER) $(PROJECT)$(RESET)\n"
	lfc --target-compiler=$(TARGET_COMPILER) $(PROJECT)
	@printf "$(GREEN)End of process$(RESET)\n"
deploy: compiler
	@if [ "$(MODE)" = "simulation" ]; then \
		printf "$(YELLOW)Simulation mode detected — deploy (packaging) will not run.$(RESET)\n"; \
	else \
		rm -rf ./deploy_all_files; \
		mkdir -p ./deploy_all_files; \
		rm -rf ./deploy_all_files/*; \
		mkdir -p deploy_all_files/bin;\
		printf "$(GREEN)Packaging software and hardware implementation...$(RESET)\n"; \
		printf "$(GREEN)Copying Verilog files...$(RESET)\n"; \
		cp ./codesign-gen/$(basename $(PROJECT))/src-gen/hardware/_FpgaTop/build/ZCU106Wrapper.v ./deploy_all_files ; \
    cp ./codesign-gen/$(basename $(PROJECT))/src-gen/reactor-chisel/fpga-tidbits/src/main/resources/verilog/Q_srl.v ./deploy_all_files ; \
		printf "$(GREEN)Copying library files...$(RESET)\n"; \
		cp -r ./codesign-gen/$(basename $(PROJECT))/lib ./deploy_all_files ; \
		cp -r ./bin/$(basename $(PROJECT)) ./deploy_all_files/bin/ ; \
		cp -r ./$(PROJECT) ./deploy_all_files/$(basename $(PROJECT))_SNAPSHOT_$(TIMESTAMP).lf ; \
		printf "$(GREEN)Compressing...$(RESET)\n"; \
		tar -czf ./$(basename $(PROJECT))_$(TIMESTAMP)_deploy.tar.gz \
			./deploy_all_files/ZCU106Wrapper.v \
      ./deploy_all_files/Q_srl.v \
			./deploy_all_files/lib \
			./deploy_all_files/bin/$(basename $(PROJECT)) \
			./deploy_all_files/$(basename $(PROJECT))_SNAPSHOT_$(TIMESTAMP).lf; \
		printf "$(RED)End of packaging$(RESET)\n"; \
	fi
clean:
	@printf "$(GREEN)Cleaning artifacts...$(RESET)\n"
	@printf "$(RED)rm -rf codesign-gen$(RESET)\n"
	rm -rf codesign-gen
