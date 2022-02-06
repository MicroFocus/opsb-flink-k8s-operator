# ITOM Flink Orchestrator for Kubernetes

Sample Kubernetes controller for [Flink](https://flink.apache.org/) resources.
The documentation here currently only discusses deployment with Custom Resource Definition (CRD)
targeting Flink's application mode with native Kubernetes integration.

Note: the project can be opened in IntelliJ by opening the [root `build.gradle`](build.gradle) as a Project.
It currently builds with Java 17 as configured [here](buildSrc/build.gradle) and [here](buildSrc/src/main/groovy/itom-java-plugin.gradle).
For a basic build simply run:

```
./gradlew build
```

## CRD

The YAML template is [here](helm/flork/templates/flink-job-crd.yaml),
with the corresponding POJO [here](flork-model/src/main/java/com/itom/flork/kubernetes/api/v1/model/FlinkJobCustomResource.java).
Note that the embedded Kubernetes resources use the schema from the official OpenAPI spec,
see also the [flattening helper](json-schema-flattener).

Note that the CRD is *not* in the special `crds` Helm folder.
The chart with the controller must be installed before any consumers of the CRD,
so it cannot be a sub-chart of any other chart that intends to use the CRD.

## Controller

The flow is as follows:

1. A [handler](flork-controller-core/src/main/kotlin/com/itom/flork/kubernetes/api/v1/handlers/FlinkJobHandler.kt)
receives resource updates from Kubernetes and delegates to a reconciler.
One handler instance will manage either 1 or all namespaces, more on this in the multi-tenancy section below.
2. The [reconciler](flork-controller-core/src/main/kotlin/com/itom/flork/kubernetes/api/v1/reconcilers/CoroutineFlinkJobReconciler.kt)
manages phasers for the different resources and basic cleanup.
3. The [phaser](flork-controller-core/src/main/kotlin/com/itom/flork/kubernetes/api/v1/reconcilers/phasers/CoroutineFlinkJobReconcilerPhaser.kt)
decides which [phases](flork-controller-core/src/main/kotlin/com/itom/flork/kubernetes/api/v1/reconcilers/phases) to execute and in which order.

High availability of the controller(s) is supported and the default number of replicas is 2.

### Deployment

The actual deployment is done by delegating to the classes from `flink-kubernetes`.
However, they are shadowed in the [`flink-kubernetes-shadow`](flink-kubernetes-shadow/build.gradle) module
so that a different version of Fabric8's Kubernetes Client can be used for the controller.

Note that the controller tries to communicate with Flink's job manager with the official client
(to stop/cancel or fetch job status),
and it assumes that, if TLS is enabled for REST communication in the Flink cluster,
the controller gets Flink's CA certificate injected in a trust store,
and Flink will trust the controller's certificate if needed;
see `getDescriptorWithTlsIfNeeded` in [`FlorkUtils`](flork-controller-core/src/main/kotlin/com/itom/flork/kubernetes/api/utils/FlorkUtils.kt)
and [this sample script](microservice/src/main/container-resources/flork/bin/tls-stores-setup.sh).

### Validation

A class for resource validation is [available](flork-controller-core/src/main/java/com/itom/flork/kubernetes/api/v1/validators/FlinkJobValidator.java).
The sample Helm chart [takes this into account](helm/flork/templates/flork-admission-webhooks.yaml).

## Multi-tenancy

A very simple multi-tenancy model with Kubernetes Namespace granularity is supported.
During installation, the user could specify different tenants, each of which can manage 1 or more namespaces.
Each tenant gets its own Kubernetes Deployment with a corresponding replica set (with 2 replicas each by default),
each of which would only process resources from the namespace(s) it manages.
Alternatively, a single tenant can manage all namespaces with the special namespace name `*`.

These tenants and their namespaces would be specified via Helm [values](helm/flork/values.yaml) under `deployment.tenants`,
for example:

```yaml
deployment:
  tenants:
  - id: first
    namespaces:
    - foo
  - id: second
    namespaces:
    - bar
    - baz
```

All templates from the sample Helm chart take this into account.
In particular, the [RBAC definition](helm/flork/templates/flork-rbac.yaml) will bind different Service Accounts to specific namespaces,
so they can only manage resources there.

A caveat: the validation step described above can be done by any controller regardless of tenant.
It's not possible to constraint validation to specific instances because that would allow creation of resources that could skip validation.

## Customization

To deploy a Flink cluster, 3 resources are prepared locally by the controller:
the `flink-conf.yaml` and two pod templates for job and task manager(s);
see `prepareConfFilesFromSpec` in [here](flork-controller-core/src/main/kotlin/com/itom/flork/kubernetes/api/utils/FlinkConfUtils.kt).
Before writing the corresponding files to (local, ephemeral) disk,
the code will search for [decorators](flork-model/src/main/java/com/itom/flork/kubernetes/api/plugins) using Java's `ServiceLoader` features.
Thus, a consumer could take a base container image and extend it with custom logic by simply adding jars with implementers of the decorator interfaces.

The decorators get an instance of [`florkConf`](flork-model/src/main/java/com/itom/flork/kubernetes/api/v1/model/FlorkConf.java) which,
since the CRD specifies `x-kubernetes-preserve-unknown-fields: true` for it,
may contain custom properties and/or nested objects.

## Inversion of control and dependency injection

Some helper classes for IoC are included in the `flork-controller-ioc` module.
For example, the REST controller for validation endpoints can be found
[here](flork-controller-ioc/src/main/java/com/itom/flork/kubernetes/api/v1/controllers/webhooks/ValidatingWebhooksController.java),
and the Kubernetes controller [here](flork-controller-ioc/src/main/java/com/itom/flork/kubernetes/api/v1/controllers/FlinkResourceController.java).

### Spring Boot

A sample module with bindings for Spring Boot is available in the [microservice module](microservice).
A corresponding [`Dockerfile`](docker/Dockerfile) is also provided.
Docker's context can be prepared with:

```
./gradlew build setUpDockerContext
```

It will be configured in `build/docker/`. The image can then be built with something like

```
docker build ... -f docker/Dockerfile build/docker
```

For example, if you have a minikube instance with the
[registry addon](https://minikube.sigs.k8s.io/docs/handbook/pushing/#4-pushing-to-an-in-cluster-using-registry-addon),
you could use these commands:

```
docker build -t "$(minikube ip):5000/test/itom-flork:latest" -f docker/Dockerfile build/docker
docker push "$(minikube ip):5000/test/itom-flork:latest"
```

## Helm chart

In addition to what has been mentioned above,
the [Helm chart](helm/flork) includes some sample certificates for the webhook endpoints,
but they are only valid if you install in a namespace called `flork`.
You could install with a command like

```
helm -n flork install flork helm/flork --set images.florkService.imageTag=latest
```

If, for whatever reason, you build newer images of the controller,
change the tag to something like `latest.1` and adjust the `helm` call accordingly.

## Sample resources

See the [kubernetes folder](kubernetes).
If you're using minikube with the registry addon as mentioned above,
push the Flink image to the local registry as well;
from Kubernetes' point of view in minikube,
the local registry is accessible at `localhost:5000`.

## Missing or not yet considered

- Ingress.
- [Java Operator SDK](https://javaoperatorsdk.io/).

## Contributing

See [here](.github/CONTRIBUTING.md).
