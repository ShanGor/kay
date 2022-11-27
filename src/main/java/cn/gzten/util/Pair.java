package cn.gzten.util;


//public record Pair<F, S>(F first, S second) {}


import lombok.AllArgsConstructor;

import java.util.Map;

@AllArgsConstructor
public class Pair<F, S> implements Map.Entry {
    F key;
    S value;
    @Override
    public F getKey() {
        return key;
    }

    @Override
    public S getValue() {
        return value;
    }

    public F first() {
        return key;
    }

    public S second() {
        return value;
    }

    @Override
    public Object setValue(Object value) {
        this.value = (S)value;
        return this;
    }

}
