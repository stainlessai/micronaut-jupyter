Examples
===

In this directory are a collection of example Micronaut services that
demonstrate the ability to use various features of the Micronaut kernel.

Each of these examples makes some assumptions about your environment:
- Jupyter is installed and configured, and you are able to run Jupyter and
  access the UI.
- `/usr/local/share/jupyter` is configured as a Jupyter data directory (default
  on Ubuntu), and it is accessible by the example app.
  - This can be customized by setting the `jupyter.kernel.location` property
    for the example.

## Running the Examples
Start the app by opening the example directory (i.e. `examples/basic-service/`)
and running:
```bash
../../gradlew run
```

If Jupyter is already running, then navigate to the example directory from
within the Jupyter ui. Otherwise, start Jupyter inside the example directory,
and open the Jupyter endpoint in your browser (the default endpoint is
http://localhost:8888/lab).

## Run the Notebooks
The notebooks are located in the `notebooks/` directory for each example. Open
the desired notebook and run all of its cells in order (either individually or
at once).