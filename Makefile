.PHONY: test fmt lint clean

test:
	lein test

fmt:
	lein cljfmt fix

lint:
	lein cljfmt check

clean:
	lein clean
