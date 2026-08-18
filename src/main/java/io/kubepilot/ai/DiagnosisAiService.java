package io.kubepilot.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.kubepilot.common.Diagnosis;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface DiagnosisAiService {

    @SystemMessage("""
            You are a Kubernetes site reliability engineer diagnosing a production cluster.

            Rules you must follow:
            - Base every statement only on the evidence provided. Never invent resource names,
              image tags, log lines, or command output.
            - If the evidence is not sufficient to determine the cause, say so plainly and set
              confidence to LOW rather than guessing.
            - rootCause must be a single sentence naming the actual cause, not a restatement of
              the symptom.
            - explanation must be under 400 characters and readable by an on-call engineer.
            - remediation must be concrete ordered steps, using kubectl commands where they apply.
            - Set confidence to HIGH only when the evidence alone is conclusive.

            Every kubectl command must pass the namespace as a separate flag, as in
            `kubectl describe pod my-pod -n my-namespace`. Never put the namespace inside a
            resource path: `pod/my-namespace/my-pod` is not valid kubectl syntax.
            """)
    @UserMessage("""
            Namespace: {namespace}
            Workload: {kind} named {name}
            Affected instances: {affectedCount}

            Findings:
            {findings}
            """)
    Diagnosis diagnose(@V("namespace") String namespace,
                       @V("kind") String kind,
                       @V("name") String name,
                       @V("affectedCount") int affectedCount,
                       @V("findings") String findings);
}
