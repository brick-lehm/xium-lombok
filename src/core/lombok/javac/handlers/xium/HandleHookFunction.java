package lombok.javac.handlers.xium;

import static lombok.javac.handlers.JavacHandlerUtil.injectMethod;
import static lombok.javac.handlers.JavacHandlerUtil.recursiveSetGeneratedBy;
import static lombok.javac.handlers.JavacHandlerUtil.typeMatches;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;

import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.HandlerPriority;
import lombok.javac.JavacASTAdapter;
import lombok.javac.JavacASTVisitor;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.spi.Provides;
import lombok.xium.HookFunction;
import lombok.xium.HookFunctionOn;

@Provides(JavacASTVisitor.class)
@HandlerPriority(32768)
public class HandleHookFunction extends JavacASTAdapter {
	@Override public void visitType(JavacNode typeNode, JCClassDecl type) {
		if (typeNode.getKind() != Kind.TYPE) return;
		HookMethods hookMethods = findHookMethods(typeNode);
		if (hookMethods.methods.isEmpty()) return;
		if (!hookMethods.valid) return;
		injectHooksIntoConstructors(typeNode, type, hookMethods.methods);
	}

	private HookMethods findHookMethods(JavacNode typeNode) {
		java.util.List<HookMethod> methods = new ArrayList<HookMethod>();
		boolean valid = true;
		for (JavacNode child : typeNode.down()) {
			if (child.getKind() != Kind.METHOD) continue;
			JCMethodDecl method = (JCMethodDecl) child.get();
			JavacNode annotationNode = findHookFunctionAnnotation(child);
			if (annotationNode == null) continue;
			AnnotationValues<HookFunction> annotation = lombok.javac.handlers.JavacHandlerUtil.createAnnotation(HookFunction.class, annotationNode);
			if (annotation.getInstance().on() != HookFunctionOn.CONSTRUCTOR) continue;
			valid &= validateHookMethod(child, method, annotationNode);
			methods.add(new HookMethod(child, method));
		}
		Collections.sort(methods, new Comparator<HookMethod>() {
			@Override public int compare(HookMethod left, HookMethod right) {
				return left.method.name.toString().compareTo(right.method.name.toString());
			}
		});
		return new HookMethods(methods, valid);
	}

	private JavacNode findHookFunctionAnnotation(JavacNode methodNode) {
		for (JavacNode child : methodNode.down()) {
			if (child.getKind() != Kind.ANNOTATION) continue;
			JCAnnotation annotation = (JCAnnotation) child.get();
			if (typeMatches(HookFunction.class, child, annotation.annotationType)) return child;
		}
		return null;
	}

	private boolean validateHookMethod(JavacNode methodNode, JCMethodDecl method, JavacNode annotationNode) {
		boolean valid = true;
		if (method.name.contentEquals("<init>")) {
			annotationNode.addError("@HookFunction is only supported on methods.");
			valid = false;
		}
		if (method.params != null && !method.params.isEmpty()) {
			annotationNode.addError("@HookFunction methods must not declare parameters.");
			valid = false;
		}
		if (!returnsVoid(method)) {
			annotationNode.addError("@HookFunction methods must return void.");
			valid = false;
		}
		if ((method.mods.flags & Flags.STATIC) != 0) {
			annotationNode.addError("@HookFunction methods must not be static.");
			valid = false;
		}
		return valid;
	}

	private boolean returnsVoid(JCMethodDecl method) {
		return method.restype != null && "void".equals(method.restype.toString());
	}

	private void injectHooksIntoConstructors(JavacNode typeNode, JCClassDecl type, java.util.List<HookMethod> hookMethods) {
		java.util.List<ConstructorMethod> constructors = findConstructors(typeNode);
		if (constructors.isEmpty()) {
			injectDefaultConstructor(typeNode, type, hookMethods);
			return;
		}
		for (ConstructorMethod constructor : constructors) {
			if (constructor.method.body == null) continue;
			ConstructorDelegation delegation = getConstructorDelegation(constructor.method);
			if (delegation == ConstructorDelegation.THIS_ONLY) continue;
			if (delegation == ConstructorDelegation.THIS_WITH_TAIL) {
				constructor.node.addError("@HookFunction does not support constructors that continue processing after this(...).");
				continue;
			}
			appendHookCalls(constructor.node, constructor.method, hookMethods);
		}
	}

	private java.util.List<ConstructorMethod> findConstructors(JavacNode typeNode) {
		java.util.List<ConstructorMethod> constructors = new ArrayList<ConstructorMethod>();
		for (JavacNode child : typeNode.down()) {
			if (child.getKind() != Kind.METHOD) continue;
			JCMethodDecl method = (JCMethodDecl) child.get();
			if (method.name.contentEquals("<init>") && (method.mods.flags & Flags.GENERATEDCONSTR) == 0) constructors.add(new ConstructorMethod(child, method));
		}
		return constructors;
	}

	private ConstructorDelegation getConstructorDelegation(JCMethodDecl constructor) {
		List<JCStatement> statements = constructor.body == null ? List.<JCStatement>nil() : constructor.body.stats;
		if (statements == null || statements.isEmpty()) return ConstructorDelegation.NONE;
		String callName = constructorCallName(statements.head);
		if (!"this".equals(callName)) return ConstructorDelegation.NONE;
		return statements.tail == null || statements.tail.isEmpty() ? ConstructorDelegation.THIS_ONLY : ConstructorDelegation.THIS_WITH_TAIL;
	}

	private String constructorCallName(JCStatement statement) {
		if (!(statement instanceof JCExpressionStatement)) return "";
		JCExpression expr = ((JCExpressionStatement) statement).expr;
		if (!(expr instanceof JCMethodInvocation)) return "";
		JCExpression invocation = ((JCMethodInvocation) expr).meth;
		if (invocation instanceof JCFieldAccess) return ((JCFieldAccess) invocation).name.toString();
		if (invocation instanceof JCIdent) return ((JCIdent) invocation).name.toString();
		return "";
	}

	private void injectDefaultConstructor(JavacNode typeNode, JCClassDecl type, java.util.List<HookMethod> hookMethods) {
		JavacTreeMaker maker = typeNode.getTreeMaker();
		long access = type.mods.flags & (Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE);
		JCModifiers mods = maker.Modifiers(access);
		List<JCStatement> statements = hookCallStatements(typeNode, hookMethods);
		JCBlock body = maker.Block(0L, statements);
		JCMethodDecl constructor = maker.MethodDef(mods, typeNode.toName("<init>"), null, List.<JCTypeParameter>nil(), List.<JCVariableDecl>nil(), List.<JCExpression>nil(), body, null);
		recursiveSetGeneratedBy(constructor, hookMethods.get(0).node);
		injectMethod(typeNode, hookMethods.get(0).node, constructor);
	}

	private void appendHookCalls(JavacNode constructorNode, JCMethodDecl constructor, java.util.List<HookMethod> hookMethods) {
		constructor.body.stats = constructor.body.stats.appendList(hookCallStatements(constructorNode, hookMethods));
		constructorNode.getAst().setChanged();
	}

	private List<JCStatement> hookCallStatements(JavacNode source, java.util.List<HookMethod> hookMethods) {
		JavacTreeMaker maker = source.getTreeMaker();
		ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();
		for (HookMethod hookMethod : hookMethods) {
			JCExpression receiver = maker.Ident(source.toName("this"));
			JCExpression callTarget = maker.Select(receiver, hookMethod.method.name);
			JCStatement call = maker.Exec(maker.Apply(List.<JCExpression>nil(), callTarget, List.<JCExpression>nil()));
			statements.append(recursiveSetGeneratedBy(call, hookMethod.node));
		}
		return statements.toList();
	}

	private enum ConstructorDelegation {
		NONE, THIS_ONLY, THIS_WITH_TAIL
	}

	private static class HookMethods {
		private final java.util.List<HookMethod> methods;
		private final boolean valid;

		HookMethods(java.util.List<HookMethod> methods, boolean valid) {
			this.methods = methods;
			this.valid = valid;
		}
	}

	private static class HookMethod {
		private final JavacNode node;
		private final JCMethodDecl method;

		HookMethod(JavacNode node, JCMethodDecl method) {
			this.node = node;
			this.method = method;
		}
	}

	private static class ConstructorMethod {
		private final JavacNode node;
		private final JCMethodDecl method;

		ConstructorMethod(JavacNode node, JCMethodDecl method) {
			this.node = node;
			this.method = method;
		}
	}
}
