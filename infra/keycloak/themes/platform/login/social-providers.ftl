<#macro show social>
  <div class="${properties.kcLoginMainFooterBand!}">
    <span class="${properties.kcLoginMainFooterBandItem!} ${properties.kcLoginMainFooterHelperText!}">
      ${msg("identity-provider-login-label")}
    </span>
  </div>
  <div id="kc-social-providers" class="${properties.kcFormSocialAccountSectionClass!}">
    <ul class="platform-social-list">
      <#list social.providers as p>
        <#assign brand = "">
        <#assign label = p.displayName!p.alias>
        <#switch p.alias>
          <#case "platform-naver"><#assign brand = "naver"><#assign label = msg("platformSocialNaver")><#break>
          <#case "platform-google"><#assign brand = "google"><#assign label = msg("platformSocialGoogle")><#break>
          <#case "platform-kakao"><#assign brand = "kakao"><#assign label = msg("platformSocialKakao")><#break>
        </#switch>
        <li>
          <a id="social-${p.alias}" class="platform-social-button <#if brand?has_content>platform-social-button--${brand}</#if>"
             href="${p.loginUrl}" data-once-link data-disabled-class="platform-social-disabled">
            <#if brand?has_content>
              <span class="platform-social-icon platform-social-icon--${brand}" aria-hidden="true"></span>
            </#if>
            <span>${label}</span>
          </a>
        </li>
      </#list>
    </ul>
  </div>
</#macro>
