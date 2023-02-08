package com.taobao.arthas.core.command.model;

import java.util.List;

import com.taobao.arthas.core.command.klass100.InjectCommand.InjectEntry;

public class InjectModel extends ResultModel {
    
    private int id;

    private String injectedClass;
    private List<InjectEntry> injectEntries;
    
    public InjectModel() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getInjectedClass() {
        return injectedClass;
    }

    public void setInjectedClass(String injectedClass) {
        this.injectedClass = injectedClass;
    }

   
    public List<InjectEntry> getInjectEntries() {
        return injectEntries;
    }

    public void setInjectEntries(List<InjectEntry> injectEntries) {
        this.injectEntries = injectEntries;
    }

    @Override
    public String getType() {
        return "inject";
    }

}
