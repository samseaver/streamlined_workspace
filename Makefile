TARGET ?= /kb/deployment
DEPLOY_RUNTIME ?= /kb/runtime
JAVA_HOME ?= $(DEPLOY_RUNTIME)/java

TOP_DIR = ../..
TOOLS_DIR = $(TOP_DIR)/tools
WRAP_PERL_TOOL = wrap_perl
WRAP_PERL_SCRIPT = bash $(TOOLS_DIR)/$(WRAP_PERL_TOOL).sh
SRC_PERL = $(wildcard scripts/*.pl)

SERVICE = workspace
TPAGE ?= $(DEPLOY_RUNTIME)/bin/tpage

ANT = ant

# make sure our make test works
.PHONY : test

default: build-libs wrap-perl-scripts

deploy: wrap-perl-scripts configure-scripts deploy-client-libs

deploy-client-libs:
	mkdir -p $(TARGET)/lib/
	cp -rv lib/* $(TARGET)/lib/

wrap-perl-scripts:
	export KB_TOP=$(TARGET); \
	export KB_RUNTIME=$(DEPLOY_RUNTIME); \
	export KB_PERL_PATH=$(TARGET)/lib ; \
	for src in $(SRC_PERL) ; do \
		basefile=`basename $$src`; \
		base=`basename $$src .pl`; \
		echo install $$src $$base ; \
		cp $$src $(TARGET)/plbin ; \
		$(WRAP_PERL_SCRIPT) "$(TARGET)/plbin/$$basefile" $(TARGET)/bin/$$base ; \
	done

build-libs:
	@#TODO at some point make dependent on compile - checked in for now.
	$(ANT) compile

configure-scripts:
	$(TPAGE) \
                --define defaultURL=$(DEFAULT_SCRIPT_URL) \
                --define localhostURL=http://127.0.0.1:$(SERVICE_PORT) \
                --define devURL=$(DEV_SCRIPT_URL) \
                lib/Bio/KBase/$(SERVICE)/ScriptConfig.tt > lib/Bio/KBase/$(SERVICE)/ScriptConfig.pm