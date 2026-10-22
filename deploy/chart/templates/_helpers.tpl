{{- define "dentistdss.labels" -}}
app.kubernetes.io/part-of: dentistdss
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
{{- end }}

{{- define "dentistdss.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end }}

{{/*
Image reference: (list $ "<name>" "<tag>") -> <registry>/dentistdss-<name>:<tag>,
pinned @<digest> when global.imageDigests.<name> is set (the release workflow
writes digests for production, where Kyverno verifies signatures by digest).
*/}}
{{- define "dentistdss.image" -}}
{{- $root := index . 0 -}}
{{- $name := index . 1 -}}
{{- $digest := index ($root.Values.global.imageDigests | default dict) $name -}}
{{- printf "%s/dentistdss-%s:%s" $root.Values.global.imageRegistry $name (index . 2) -}}
{{- with $digest }}@{{ . }}{{ end -}}
{{- end -}}
