Kubernetes doesn't allow references in CRDs,
but their [OpenAPI spec](https://github.com/kubernetes/kubernetes/blob/master/api/openapi-spec/swagger.json) uses them.
This small helper takes that spec and resolves all references to flatten the schema.

To build, run the following from this repository's root folder:

```
./gradlew -PjsonSchemaFlattener :json-schema-flattener:shadowJar
```

The resulting jar will be in this module's build output under `build/libs/`.
You can then call the jar file as a CLI like this:

```
java -jar json-schema-flattener/build/libs/json-schema-flattener-0.0.1-SNAPSHOT-shadow.jar -f <SPEC_JSON> -r <SCHEMA_ROOT>
```

Samples for `<SCHEMA_ROOT>` if you download and pass the Kubernetes' spec linked above in `-f`:

- `io.k8s.api.core.v1.PodSpec`
- `io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta`

The output will be a file called `flattened_schema.yaml` (unless you change it with `-o`).
