package com.agentscopea2a.agent.dimension;

public class DimensionException extends RuntimeException{

    public DimensionException(String message){
        super(message);
    }

    public DimensionException(String message,Throwable cause){
        super(message,cause);
    }
}
