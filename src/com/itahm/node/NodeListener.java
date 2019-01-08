package com.itahm.node;

public interface NodeListener {
	public void onSuccess(Node node, long time);
	public void onFailure(Node node);
}
