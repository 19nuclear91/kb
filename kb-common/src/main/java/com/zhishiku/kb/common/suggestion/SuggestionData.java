package com.zhishiku.kb.common.suggestion;

import java.util.List;

/** 服务端生成的材料身份及用于表单展示的受控建议。 */
public final class SuggestionData {
    private SuggestionData() {
    }

    public record Block(int index, String kind, String text, String heading, Integer page, String tableCells) {
        @Override
        public String toString() {
            return "Block";
        }

    }

    public record Category(long id, List<String> path) {
        @Override
        public String toString() {
            return "Category";
        }

        public Category {
            path = List.copyOf(path);
        }
    }

    public record Material(List<Block> blocks, List<Category> categories) {
        public Material {
            blocks = List.copyOf(blocks);
            categories = List.copyOf(categories);
        }
    }

    public record Source(int index, String heading, Integer page) {
        @Override
        public String toString() {
            return "Source";
        }

    }

    public record Text(String value, List<Source> sources) {
        @Override
        public String toString() {
            return "Text";
        }

        public Text {
            sources = List.copyOf(sources);
        }
    }

    public record Classification(Category value, List<Source> sources) {
        public Classification {
            sources = List.copyOf(sources);
        }
    }

    public record Result(Text title, Text summary, Classification category) {
        @Override
        public String toString() {
            return "SuggestionResult";
        }

    }
}
