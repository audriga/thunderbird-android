package com.fsck.k9.mailstore;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.TemplateFunction;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.io.StringReader;

public class MustacheRenderer {
    private String headerIconTemplate;
    private String imageIconTemplate;

   public MustacheRenderer() throws IOException {
        loadLambdaTemplates();
    }

    private void loadLambdaTemplates() throws IOException {
        /*
        String jsonContent = Files.readString(Paths.get("src/main/resources/icon-configs/icon_map_FontAwesome.json"));
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> jsonMap = objectMapper.readValue(jsonContent, Map.class);
        headerIconTemplate = (String) jsonMap.get("headerIconTemplate");
        imageIconTemplate = (String) jsonMap.get("imageIconTemplate");
        */
    }

    private List<TemplateFunction> getIconLambdas() {
        return Arrays.asList(new TemplateFunction() {
            @Override
            public String apply(String input) {
                return headerIconTemplate.replace("{{iconName}}", input);
            }
        }, new TemplateFunction() {
            @Override
            public String apply(String input) {
                return imageIconTemplate.replace("{{iconName}}", input);
            }
        });
    }

    /* TODO maybe change references subtemplate in mustache template instead? */
    public String renderSubtemplate(JsonLd jsonLd) throws IOException {

        //String templatePath = "templates/sub_Default_materialdesign.html.mustache"; // TODO add logic for mapping

        DefaultMustacheFactory mf = new DefaultMustacheFactory();

        Mustache mustache = mf.compile(new StringReader(TEMPLATE_SUB), "sub_Default_materialdesign.html.mustache");
        //Mustache mustache = mf.compile(templatePath);

        Map<String, Object> context = jsonLd.getData();

        try (StringWriter writer = new StringWriter()) {
            mustache.execute(writer, context).flush();
            return writer.toString();
        }
    }

    public String render(JsonLd jsonLd) throws IOException {
        DefaultMustacheFactory mf = new DefaultMustacheFactory();
        List<TemplateFunction> iconLambdas = getIconLambdas();
        String renderedSubTemplate = renderSubtemplate(jsonLd);

        //String templatePath = "templates/Default_materialdesign.html.mustache"; // TODO add logic for mapping

        Mustache mustache = mf.compile(new StringReader(TEMPLATE), "Default_materialdesign.html.mustache");

        //Mustache mustache = mf.compile(templatePath);

        Map<String, Object> context = jsonLd.getData();
        // context.put("iconLambdas", iconLambdas) TODO add feature,
        context.put("ld2hSubtemplateContent", renderedSubTemplate);

        try (StringWriter writer = new StringWriter()) {
            mustache.execute(writer, context).flush();
            return writer.toString();
        }
    }

    public static String CSS_HEAD = "<head><link href=\"https://unpkg.com/material-components-web@latest/dist/material-components-web.min.css\" rel=\"stylesheet\"></head>";

    public static String TEMPLATE = "<div class=\"mdc-card demo-card demo-ui-control\"  style=\"width: 350px; margin: 48px 0\">\r\n    <div class=\"mdc-card__primary-action demo-card__primary-action mdc-ripple-upgraded\" tabindex=\"0\" style=\"--mdc-ripple-fg-size: 210px; --mdc-ripple-fg-scale: 1.794660602651823; --mdc-ripple-fg-translate-start: 123px, -74px; --mdc-ripple-fg-translate-end: 70px, -50px;\">\r\n        {{#thumbnailUrl}}<div class=\"mdc-card__media mdc-card__media--square demo-card__media\" style=\"background-image: url(\'{{&thumbnailUrl}}\');\" alt=\"Picture showing {{@type}}\"></div>{{/thumbnailUrl}}\r\n        {{#thumbnail}}<div class=\"mdc-card__media mdc-card__media--square demo-card__media\" style=\"background-image: url(\'{{&thumbnail}}\');\" alt=\"Picture showing {{@type}}\"></div>{{/thumbnail}}\r\n        {{^thumbnailUrl}} {{^thumbnail}} {{#imageIconTemplate}}{{iconName}}{{/imageIconTemplate}}{{/thumbnail}}{{/thumbnailUrl}}\r\n        <div class=\"demo-card__primary\" style=\"padding: 1rem;\">\r\n            {{#ld2hSubtemplateContent}}\r\n                {{{ld2hSubtemplateContent}}}\r\n            {{/ld2hSubtemplateContent}}\r\n        </div>\r\n    </div>\r\n    <div class=\"mdc-card__actions\">\r\n        <div class=\"mdc-card__action-buttons\">\r\n            {{#potentialAction}}<button class=\"mdc-button mdc-card__action mdc-card__action--button mdc-ripple-upgraded\" onclick=\"window.open(\'{{target}}\', \'_blank\');\"><span class=\"mdc-button__ripple\"></span>{{name}}</button>{{/potentialAction}}\r\n        </div>\r\n    </div>\r\n</div>";


    public static String TEMPLATE_SUB = "<h2 class=\"demo-card__title mdc-typography mdc-typography--headline6\">{{name}}</h2>\r\n\r\n<h3 class=\"demo-card__subtitle mdc-typography mdc-typography--subtitle2\">{{description}}</h3>";



}
