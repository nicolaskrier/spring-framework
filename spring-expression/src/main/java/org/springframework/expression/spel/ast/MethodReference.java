/*
 * Copyright 2002-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.expression.spel.ast;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

import org.jspecify.annotations.Nullable;

import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.ExpressionInvocationTargetException;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.CodeFlow;
import org.springframework.expression.spel.ExpressionState;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.support.ReflectiveMethodExecutor;
import org.springframework.expression.spel.support.ReflectiveMethodResolver;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

/**
 * Expression language AST node that represents a method reference.
 *
 * @author Andy Clement
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.0
 */
public class MethodReference extends SpelNodeImpl {

	private final boolean nullSafe;

	private final String name;

	private @Nullable Character originalPrimitiveExitTypeDescriptor;

	private volatile @Nullable CachedMethodExecutor cachedExecutor;


	public MethodReference(boolean nullSafe, String methodName, int startPos, int endPos, SpelNodeImpl... arguments) {
		super(startPos, endPos, arguments);
		this.name = methodName;
		this.nullSafe = nullSafe;
	}


	/**
	 * Does this node represent a null-safe method reference?
	 * @since 6.0.13
	 */
	@Override
	public final boolean isNullSafe() {
		return this.nullSafe;
	}

	/**
	 * Get the name of the referenced method.
	 */
	public final String getName() {
		return this.name;
	}

	@Override
	protected ValueRef getValueRef(ExpressionState state) throws EvaluationException {
		@Nullable Object[] arguments = getArguments(state);
		if (state.getActiveContextObject().getValue() == null) {
			throwIfNotNullSafe(getArgumentTypes(arguments));
			return ValueRef.NullValueRef.INSTANCE;
		}
		return new MethodValueRef(state, arguments);
	}

	@Override
	public TypedValue getValueInternal(ExpressionState state) throws EvaluationException {
		EvaluationContext evaluationContext = state.getEvaluationContext();
		Object value = state.getActiveContextObject().getValue();
		TypeDescriptor targetType = state.getActiveContextObject().getTypeDescriptor();
		@Nullable Object[] arguments = getArguments(state);
		TypedValue result = getValueInternal(evaluationContext, value, targetType, arguments);
		updateExitTypeDescriptor();
		return result;
	}

	private TypedValue getValueInternal(EvaluationContext evaluationContext,
			@Nullable Object value, @Nullable TypeDescriptor targetType, @Nullable Object[] arguments) {

		List<TypeDescriptor> argumentTypes = getArgumentTypes(arguments);
		if (value == null) {
			throwIfNotNullSafe(argumentTypes);
			return TypedValue.NULL;
		}

		MethodExecutor executorToUse = getCachedExecutor(evaluationContext, value, targetType, argumentTypes);
		if (executorToUse != null) {
			try {
				return executorToUse.execute(evaluationContext, value, arguments);
			}
			catch (AccessException ex) {
				// Two reasons this can occur:
				// 1. the method invoked actually threw a real exception
				// 2. the method invoked was not passed the arguments it expected and
				//    has become 'stale'

				// In the first case we should not retry, in the second case we should see
				// if there is a better suited method.

				// To determine the situation, the AccessException will contain a cause.
				// If the cause is an InvocationTargetException, a user exception was
				// thrown inside the method. Otherwise the method could not be invoked.
				throwSimpleExceptionIfPossible(value, ex);

				// At this point we know it wasn't a user problem so worth a retry if a
				// better candidate can be found.
				this.cachedExecutor = null;
			}
		}

		// either there was no accessor or it no longer existed
		executorToUse = findAccessorForMethod(argumentTypes, value, evaluationContext);
		this.cachedExecutor = new CachedMethodExecutor(
				executorToUse, (value instanceof Class<?> clazz ? clazz : null), targetType, argumentTypes);
		try {
			return executorToUse.execute(evaluationContext, value, arguments);
		}
		catch (AccessException ex) {
			// Same unwrapping exception handling as above in above catch block
			throwSimpleExceptionIfPossible(value, ex);
			throw new SpelEvaluationException(getStartPosition(), ex,
					SpelMessage.EXCEPTION_DURING_METHOD_INVOCATION, this.name,
					value.getClass().getName(), ex.getMessage());
		}
	}

	private void throwIfNotNullSafe(List<TypeDescriptor> argumentTypes) {
		if (!isNullSafe()) {
			throw new SpelEvaluationException(getStartPosition(),
					SpelMessage.METHOD_CALL_ON_NULL_OBJECT_NOT_ALLOWED,
					FormatHelper.formatMethodForMessage(this.name, argumentTypes));
		}
	}

	private @Nullable Object[] getArguments(ExpressionState state) {
		@Nullable Object[] arguments = new Object[getChildCount()];
		for (int i = 0; i < arguments.length; i++) {
			// Make the root object the active context again for evaluating the parameter expressions
			try {
				state.pushActiveContextObject(state.getScopeRootContextObject());
				arguments[i] = this.children[i].getValueInternal(state).getValue();
			}
			finally {
				state.popActiveContextObject();
			}
		}
		return arguments;
	}

	private List<TypeDescriptor> getArgumentTypes(@Nullable Object... arguments) {
		List<@Nullable TypeDescriptor> descriptors = new ArrayList<>(arguments.length);
		for (Object argument : arguments) {
			descriptors.add(TypeDescriptor.forObject(argument));
		}
		return Collections.unmodifiableList(descriptors);
	}

	private @Nullable MethodExecutor getCachedExecutor(EvaluationContext evaluationContext, Object value,
			@Nullable TypeDescriptor target, List<TypeDescriptor> argumentTypes) {

		List<MethodResolver> methodResolvers = evaluationContext.getMethodResolvers();
		if (methodResolvers.size() != 1 || !(methodResolvers.get(0) instanceof ReflectiveMethodResolver)) {
			// Not a default ReflectiveMethodResolver - don't know whether caching is valid
			return null;
		}

		CachedMethodExecutor executorToCheck = this.cachedExecutor;
		if (executorToCheck != null && executorToCheck.isSuitable(value, target, argumentTypes)) {
			return executorToCheck.get();
		}
		this.cachedExecutor = null;
		return null;
	}

	private MethodExecutor findAccessorForMethod(List<TypeDescriptor> argumentTypes, Object targetObject,
			EvaluationContext evaluationContext) throws SpelEvaluationException {

		AccessException accessException = null;
		for (MethodResolver methodResolver : evaluationContext.getMethodResolvers()) {
			try {
				MethodExecutor methodExecutor = methodResolver.resolve(
						evaluationContext, targetObject, this.name, argumentTypes);
				if (methodExecutor != null) {
					return methodExecutor;
				}
			}
			catch (AccessException ex) {
				accessException = ex;
				break;
			}
		}

		String method = FormatHelper.formatMethodForMessage(this.name, argumentTypes);
		String className = FormatHelper.formatClassNameForMessage(
				targetObject instanceof Class<?> clazz ? clazz : targetObject.getClass());
		if (accessException != null) {
			throw new SpelEvaluationException(
					getStartPosition(), accessException, SpelMessage.PROBLEM_LOCATING_METHOD, method, className);
		}
		else {
			throw new SpelEvaluationException(getStartPosition(), SpelMessage.METHOD_NOT_FOUND, method, className);
		}
	}

	/**
	 * Decode the AccessException, throwing a lightweight evaluation exception or,
	 * if the cause was a RuntimeException, throw the RuntimeException directly.
	 */
	private void throwSimpleExceptionIfPossible(Object value, AccessException ex) {
		if (ex.getCause() instanceof InvocationTargetException cause) {
			Throwable rootCause = cause.getCause();
			if (rootCause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new ExpressionInvocationTargetException(getStartPosition(),
					"A problem occurred when trying to execute method '" + this.name +
					"' on object of type [" + value.getClass().getName() + "]", rootCause);
		}
	}

	private void updateExitTypeDescriptor() {
		CachedMethodExecutor executorToCheck = this.cachedExecutor;
		if (executorToCheck != null && executorToCheck.get() instanceof ReflectiveMethodExecutor reflectiveMethodExecutor) {
			Method method = reflectiveMethodExecutor.getMethod();
			String descriptor = CodeFlow.toDescriptor(method.getReturnType());
			if (isNullSafe() && CodeFlow.isPrimitive(descriptor) && (descriptor.charAt(0) != 'V')) {
				this.originalPrimitiveExitTypeDescriptor = descriptor.charAt(0);
				this.exitTypeDescriptor = CodeFlow.toBoxedDescriptor(descriptor);
			}
			else {
				this.exitTypeDescriptor = descriptor;
			}
		}
	}

	@Override
	public String toStringAST() {
		StringJoiner sj = new StringJoiner(",", "(", ")");
		for (int i = 0; i < getChildCount(); i++) {
			sj.add(getChild(i).toStringAST());
		}
		return this.name + sj;
	}

	/**
	 * A method reference is compilable if it has been resolved to a reflectively accessible method
	 * and the child nodes (arguments to the method) are also compilable.
	 */
	@Override
	public boolean isCompilable() {
		CachedMethodExecutor executorToCheck = this.cachedExecutor;
		if (executorToCheck == null || executorToCheck.hasProxyTarget() ||
				!(executorToCheck.get() instanceof ReflectiveMethodExecutor executor)) {
			return false;
		}

		for (SpelNodeImpl child : this.children) {
			if (!child.isCompilable()) {
				return false;
			}
		}
		if (executor.didArgumentConversionOccur()) {
			return false;
		}

		Method method = executor.getMethod();
		return (Modifier.isPublic(method.getModifiers()) && executor.getPublicDeclaringClass() != null);
	}

	@Override
	public void generateCode(MethodVisitor mv, CodeFlow cf) {
		CachedMethodExecutor executorToCheck = this.cachedExecutor;
		if (executorToCheck == null || !(executorToCheck.get() instanceof ReflectiveMethodExecutor methodExecutor)) {
			throw new IllegalStateException("No applicable cached executor found: " + executorToCheck);
		}
		Method method = methodExecutor.getMethod();

		Class<?> publicDeclaringClass = methodExecutor.getPublicDeclaringClass();
		Assert.state(publicDeclaringClass != null,
				() -> "Failed to find public declaring class for method: " + method);

		String classDesc = publicDeclaringClass.getName().replace('.', '/');
		boolean isStatic = Modifier.isStatic(method.getModifiers());
		String descriptor = cf.lastDescriptor();

		if (descriptor == null && !isStatic) {
			// Nothing on the stack but something is needed
			cf.loadTarget(mv);
		}

		Label skipIfNull = null;
		if (isNullSafe() && (descriptor != null || !isStatic)) {
			skipIfNull = new Label();
			Label continueLabel = new Label();
			mv.visitInsn(DUP);
			mv.visitJumpInsn(IFNONNULL, continueLabel);
			CodeFlow.insertCheckCast(mv, this.exitTypeDescriptor);
			mv.visitJumpInsn(GOTO, skipIfNull);
			mv.visitLabel(continueLabel);
		}

		if (descriptor != null && isStatic) {
			// A static method call will not consume what is on the stack, so
			// it needs to be popped off.
			mv.visitInsn(POP);
		}

		if (CodeFlow.isPrimitive(descriptor)) {
			CodeFlow.insertBoxIfNecessary(mv, descriptor.charAt(0));
		}

		if (!isStatic && (descriptor == null || !descriptor.substring(1).equals(classDesc))) {
			CodeFlow.insertCheckCast(mv, "L" + classDesc);
		}

		generateCodeForArguments(mv, cf, method, this.children);
		boolean isInterface = publicDeclaringClass.isInterface();
		int opcode = (isStatic ? INVOKESTATIC : isInterface ? INVOKEINTERFACE : INVOKEVIRTUAL);
		mv.visitMethodInsn(opcode, classDesc, method.getName(), CodeFlow.createSignatureDescriptor(method),
				isInterface);
		cf.pushDescriptor(this.exitTypeDescriptor);

		if (this.originalPrimitiveExitTypeDescriptor != null) {
			// The output of the accessor will be a primitive but from the block above it might be null,
			// so to have a 'common stack' element at the skipIfNull target we need to box the primitive.
			CodeFlow.insertBoxIfNecessary(mv, this.originalPrimitiveExitTypeDescriptor);
		}

		if (skipIfNull != null) {
			if ("V".equals(this.exitTypeDescriptor)) {
				// If the method return type is 'void', we need to push a null object
				// reference onto the stack to satisfy the needs of the skipIfNull target.
				mv.visitInsn(ACONST_NULL);
			}
			mv.visitLabel(skipIfNull);
		}
	}


	private class MethodValueRef implements ValueRef {

		private final EvaluationContext evaluationContext;

		private final @Nullable Object value;

		private final @Nullable TypeDescriptor targetType;

		private final @Nullable Object[] arguments;

		public MethodValueRef(ExpressionState state, @Nullable Object[] arguments) {
			this.evaluationContext = state.getEvaluationContext();
			this.value = state.getActiveContextObject().getValue();
			this.targetType = state.getActiveContextObject().getTypeDescriptor();
			this.arguments = arguments;
		}

		@Override
		public TypedValue getValue() {
			TypedValue result = MethodReference.this.getValueInternal(
					this.evaluationContext, this.value, this.targetType, this.arguments);
			updateExitTypeDescriptor();
			return result;
		}

		@Override
		public void setValue(@Nullable Object newValue) {
			throw new IllegalAccessError();
		}

		@Override
		public boolean isWritable() {
			return false;
		}
	}


	private static class CachedMethodExecutor {

		private final MethodExecutor methodExecutor;

		private final @Nullable Class<?> staticClass;

		private final @Nullable TypeDescriptor target;

		private final List<TypeDescriptor> argumentTypes;

		public CachedMethodExecutor(MethodExecutor methodExecutor, @Nullable Class<?> staticClass,
				@Nullable TypeDescriptor target, List<TypeDescriptor> argumentTypes) {

			this.methodExecutor = methodExecutor;
			this.staticClass = staticClass;
			this.target = target;
			this.argumentTypes = argumentTypes;
		}

		public boolean isSuitable(Object value, @Nullable TypeDescriptor target, List<TypeDescriptor> argumentTypes) {
			return ((this.staticClass == null || this.staticClass == value) &&
					ObjectUtils.nullSafeEquals(this.target, target) && this.argumentTypes.equals(argumentTypes));
		}

		public boolean hasProxyTarget() {
			return (this.target != null && Proxy.isProxyClass(this.target.getType()));
		}

		public MethodExecutor get() {
			return this.methodExecutor;
		}
	}

}
