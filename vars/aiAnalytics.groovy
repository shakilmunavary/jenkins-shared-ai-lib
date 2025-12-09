stage('Generate Resource × Rule Matrix') {
  dir(tfDir) {
    sh """
      set -euo pipefail
      PLAN=tfplan.json
      GUARDRAILS=${TMP_DIR}/jenkins-shared-ai-lib/guardrails/guardrails_v1.txt

      # Use Groovy's matrixPath directly
      : > "${matrixPath}"

      RESOURCES=\$(jq -r ".resource_changes[].address" "$PLAN")
      echo "Resources detected:"
      echo "\$RESOURCES" | sed "s/^/  - /"

      for RES in \$RESOURCES; do
        TYPE=\$(echo "\$RES" | cut -d"." -f1)
        awk -v type="\$TYPE" '
          \$0 ~ "^Resource Type:[[:space:]]*"type"\$" { inType=1; next }
          /^Resource Type:/ { inType=0 }
          inType && /^[[]/ { print; next }
        ' "\$GUARDRAILS" | while read -r RULELINE; do
          RULEID=\$(echo "\$RULELINE" | sed -n "s/.*Rule ID:[[:space:]]*\\([^]]*\\)].*/\\1/p")
          RULEDESC=\$(awk -v hdr="\$RULELINE" '
            BEGIN {found=0}
            \$0 == hdr {found=1; next}
            found && /^Rule:/ { sub(/^Rule:[[:space:]]*/, "", \$0); print; exit }
          ' "\$GUARDRAILS")
          if [ -z "\$RULEDESC" ]; then RULEDESC="Rule description not found"; fi
          printf "%s\\t%s\\t%s\\n" "\$RES" "\$RULEID" "\$RULEDESC" >> "${matrixPath}"
        done
      done
    """
  }
}
