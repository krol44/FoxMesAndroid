.PHONY: version version-check release-notes

VERSION ?=

version: ## Bump the FoxMes Android version everywhere: make version VERSION=1.1.0
	@if [ -z "$(VERSION)" ]; then \
		echo "Usage: make version VERSION=X.Y.Z"; \
		exit 1; \
	fi
	@foxmes/set-version.sh "$(VERSION)"

version-check: ## Verify all hardcoded version literals match gradle.properties
	@foxmes/set-version.sh --check

release-notes: ## Print the body the next GitHub release would publish
	@python3 foxmes/make-release-notes.py
